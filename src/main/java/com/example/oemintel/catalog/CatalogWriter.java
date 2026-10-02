package com.example.oemintel.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.oemintel.adapter.ExtractedFamily;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedAsset;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedValue;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedVariant;
import com.example.oemintel.adapter.roxtec.RoxtecAdapter;
import com.example.oemintel.crawl.UrlNormalizer;
import com.example.oemintel.storage.FileSystemBlobStore;

/**
 * Writes an extraction into the catalogue as a new immutable revision (AD-27). If the extracted content is identical
 * to the current revision, nothing new is written and reviewers are not disturbed.
 * <p>
 * Thin-slice simplification: the revision becomes CURRENT straight away. The DRAFT → assembly → normalisation path
 * (AD-21) arrives with document processing and normalisation (Steps 8 and 10).
 */
@Component
public class CatalogWriter {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public CatalogWriter(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public WriteResult write(long jobId, long oemId, String locale, long resourceId, String extractorVersion,
			ExtractedFamily family) {
		long familyId = upsertFamily(jobId, oemId, locale, family);
		String fingerprint = FileSystemBlobStore.sha256(this.json.writeValueAsBytes(family));
		Optional<String> currentFingerprint = this.jdbc.sql("""
				select r.content_fingerprint from product_family f join extraction_revision r on r.id = f.current_revision_id
				 where f.id = :familyId
				""").param("familyId", familyId).query(String.class).optional();
		if (currentFingerprint.filter(fingerprint::equals).isPresent()) {
			return new WriteResult(familyId, countVariants(familyId), false);
		}

		long revisionId = createRevision(familyId, jobId, resourceId, extractorVersion, fingerprint, family);
		insertValues(revisionId, familyId, null, familyValues(family));
		Map<String, Long> variantIds = new HashMap<>();
		for (ExtractedVariant variant : family.variants()) {
			Optional<Long> variantId = upsertVariant(familyId, oemId, locale, revisionId, variant);
			if (variantId.isEmpty()) {
				flag(familyId, "SKU_COLLISION", variant.sku(), "WARNING",
						"Article %s already belongs to another family; it was not added here".formatted(variant.sku()));
				continue;
			}
			variantIds.put(RoxtecAdapter.normalizeSku(variant.sku()), variantId.get());
			insertValues(revisionId, familyId, variantId.get(), variant.values());
		}
		insertAssets(revisionId, familyId, oemId, family.assets(), variantIds);
		insertCategory(familyId, revisionId, family.breadcrumb());
		if (family.variants().isEmpty()) {
			flag(familyId, "MISSING_SKU", "", "BLOCKING", "No articles (SKUs) were found on the page");
		}
		return new WriteResult(familyId, variantIds.size(), true);
	}

	private long upsertFamily(long jobId, long oemId, String locale, ExtractedFamily family) {
		return this.jdbc.sql("""
				insert into product_family (oem_id, locale, family_key, canonical_url, display_name, first_seen_job_id,
				                            last_seen_job_id)
				values (:oemId, :locale, :familyKey, :canonicalUrl, :name, :jobId, :jobId)
				on conflict (oem_id, locale, family_key) do update
				   set canonical_url = excluded.canonical_url, last_seen_job_id = excluded.last_seen_job_id,
				       updated_at = now()
				returning id
				""")
			.param("oemId", oemId)
			.param("locale", locale)
			.param("familyKey", family.familyKey())
			.param("canonicalUrl", family.canonicalUrl())
			.param("name", family.name())
			.param("jobId", jobId)
			.query(Long.class)
			.single();
	}

	private long createRevision(long familyId, long jobId, long resourceId, String extractorVersion,
			String fingerprint, ExtractedFamily family) {
		int number = this.jdbc.sql("select coalesce(max(revision_number), 0) + 1 from extraction_revision where family_id = :id")
			.param("id", familyId)
			.query(Integer.class)
			.single();
		Long parent = this.jdbc.sql("select current_revision_id from product_family where id = :id")
			.param("id", familyId)
			.query(Long.class)
			.optional()
			.orElse(null);
		this.jdbc.sql("update extraction_revision set status = 'SUPERSEDED' where family_id = :id and status = 'CURRENT'")
			.param("id", familyId)
			.update();
		String inputFingerprint = this.jdbc.sql("select content_hash from fetched_resource where id = :id")
			.param("id", resourceId)
			.query(String.class)
			.single();
		long revisionId = this.jdbc.sql("""
				insert into extraction_revision (family_id, revision_number, job_id, status, cause, parent_revision_id,
				                                 input_fingerprint, content_fingerprint, extractor_version, completeness,
				                                 failed_sections, source_resource_ids)
				values (:familyId, :number, :jobId, 'CURRENT', 'CRAWL', :parent, :input, :content, :version,
				        :completeness, cast(:warnings as jsonb), array[cast(:resourceId as bigint)])
				returning id
				""")
			.param("familyId", familyId)
			.param("number", number)
			.param("jobId", jobId)
			.param("parent", parent)
			.param("input", inputFingerprint)
			.param("content", fingerprint)
			.param("version", extractorVersion)
			.param("completeness", family.variants().isEmpty() ? "PARTIAL" : "COMPLETE")
			.param("warnings", this.json.writeValueAsString(family.warnings()))
			.param("resourceId", resourceId)
			.query(Long.class)
			.single();
		this.jdbc.sql("""
				update product_family
				   set current_revision_id = :revisionId, display_name = :name, version = version + 1, updated_at = now(),
				       review_status = case when review_status = 'APPROVED' then 'NEEDS_REREVIEW' else review_status end
				 where id = :familyId
				""").param("revisionId", revisionId).param("name", family.name()).param("familyId", familyId).update();
		return revisionId;
	}

	private List<ExtractedValue> familyValues(ExtractedFamily family) {
		List<ExtractedValue> values = new ArrayList<>();
		if (family.name() != null) {
			values.add(new ExtractedValue("Name", family.name(), "JSON_LD", "$.name", family.name()));
		}
		if (family.description() != null) {
			values.add(new ExtractedValue("Description", family.description(), "JSON_LD", "$.description",
					family.description()));
		}
		values.addAll(family.familyValues());
		return values;
	}

	private Optional<Long> upsertVariant(long familyId, long oemId, String locale, long revisionId,
			ExtractedVariant variant) {
		return this.jdbc.sql("""
				insert into product_variant (family_id, oem_id, locale, sku_display, sku_normalized, variant_label,
				                             first_seen_revision_id, last_seen_revision_id)
				values (:familyId, :oemId, :locale, :sku, :skuNormalized, :label, :revisionId, :revisionId)
				on conflict (oem_id, locale, sku_normalized) do update
				   set last_seen_revision_id = excluded.last_seen_revision_id, variant_label = excluded.variant_label,
				       updated_at = now()
				 where product_variant.family_id = excluded.family_id
				returning id
				""")
			.param("familyId", familyId)
			.param("oemId", oemId)
			.param("locale", locale)
			.param("sku", variant.sku())
			.param("skuNormalized", RoxtecAdapter.normalizeSku(variant.sku()))
			.param("label", variant.label())
			.param("revisionId", revisionId)
			.query(Long.class)
			.optional();
	}

	/**
	 * Equal values from several sources are stored once, the others recorded as corroboration; differing values for
	 * the same label are all kept so a reviewer sees the conflict instead of a silent pick.
	 */
	private void insertValues(long revisionId, long familyId, Long variantId, List<ExtractedValue> values) {
		Map<String, Merged> merged = new LinkedHashMap<>();
		for (ExtractedValue value : values) {
			String key = normalize(value.label()) + "\u0000" + normalize(value.value());
			Merged existing = merged.get(key);
			if (existing == null) {
				merged.put(key, new Merged(value, new ArrayList<>()));
			}
			else if (!existing.primary().source().equals(value.source())) {
				existing.corroborations().add(Map.of("source", value.source(), "locator", value.locator()));
			}
		}
		int position = 0;
		for (Merged entry : merged.values()) {
			ExtractedValue value = entry.primary();
			this.jdbc.sql("""
					insert into attribute_value (revision_id, family_id, variant_id, attribute_definition_id, raw_label,
					                             raw_value, value_text, position, source_type, source_locator,
					                             source_excerpt, corroborating_sources)
					values (:revisionId, :familyId, :variantId,
					        (select id from attribute_definition where is_system and lower(label) = lower(:label)),
					        :label, :value, :value, :position, :source, :locator, :excerpt, cast(:corroborations as jsonb))
					""")
				.param("revisionId", revisionId)
				.param("familyId", familyId)
				.param("variantId", variantId)
				.param("label", value.label())
				.param("value", value.value())
				.param("position", position++)
				.param("source", value.source())
				.param("locator", value.locator())
				.param("excerpt", truncate(value.excerpt(), 2000))
				.param("corroborations", this.json.writeValueAsString(entry.corroborations()))
				.update();
		}
	}

	private void insertAssets(long revisionId, long familyId, long oemId, List<ExtractedAsset> assets,
			Map<String, Long> variantIds) {
		int position = 0;
		for (ExtractedAsset asset : assets) {
			Optional<String> normalized = UrlNormalizer.normalize(asset.url());
			if (normalized.isEmpty()) {
				continue;
			}
			long sourceId = this.jdbc.sql("""
					insert into asset_source (oem_id, url, normalized_url, host)
					values (:oemId, :url, :normalized, :host)
					on conflict (oem_id, normalized_url) do update set updated_at = now()
					returning id
					""")
				.param("oemId", oemId)
				.param("url", asset.url())
				.param("normalized", normalized.get())
				.param("host", UrlNormalizer.host(normalized.get()))
				.query(Long.class)
				.single();
			Long variantId = (asset.variantSku() != null) ? variantIds.get(RoxtecAdapter.normalizeSku(asset.variantSku()))
					: null;
			this.jdbc.sql("""
					insert into product_asset_link (revision_id, family_id, variant_id, asset_source_id, role_hint, position,
					                                displayed_title)
					values (:revisionId, :familyId, :variantId, :sourceId, :role, :position, :title)
					""")
				.param("revisionId", revisionId)
				.param("familyId", familyId)
				.param("variantId", variantId)
				.param("sourceId", sourceId)
				.param("role", asset.roleHint())
				.param("position", position++)
				.param("title", asset.title())
				.update();
		}
	}

	private void insertCategory(long familyId, long revisionId, List<String> breadcrumb) {
		if (breadcrumb.isEmpty()) {
			return;
		}
		this.jdbc.sql("insert into family_category (family_id, revision_id, category_path) values (:familyId, :revisionId, :path)")
			.param("familyId", familyId)
			.param("revisionId", revisionId)
			.param("path", String.join(" > ", breadcrumb))
			.update();
	}

	/** Raises or refreshes an open flag; a dismissed flag for the same target stays dismissed. */
	private void flag(long familyId, String type, String target, String severity, String message) {
		this.jdbc.sql("""
				insert into product_flag (family_id, target_key, flag_type, severity, message)
				values (:familyId, :target, :type, :severity, :message)
				on conflict (family_id, flag_type, target_key) do update
				   set message = excluded.message, updated_at = now()
				""")
			.param("familyId", familyId)
			.param("target", target)
			.param("type", type)
			.param("severity", severity)
			.param("message", message)
			.update();
	}

	private int countVariants(long familyId) {
		return this.jdbc.sql("select count(*) from product_variant where family_id = :id and not excluded")
			.param("id", familyId)
			.query(Integer.class)
			.single();
	}

	private static String normalize(String text) {
		return (text == null) ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static String truncate(String text, int max) {
		return (text == null || text.length() <= max) ? text : text.substring(0, max);
	}

	/**
	 * @param revisionCreated {@code false} if the content was identical to the current revision
	 */
	public record WriteResult(long familyId, int skuCount, boolean revisionCreated) {
	}

	private record Merged(ExtractedValue primary, List<Map<String, String>> corroborations) {
	}

}
