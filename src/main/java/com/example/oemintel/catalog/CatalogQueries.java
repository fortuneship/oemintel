package com.example.oemintel.catalog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read models for the product pages. Values shown are those of the CURRENT revision (no overrides exist yet). */
@Component
public class CatalogQueries {

	private final JdbcClient jdbc;

	public CatalogQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<FamilyRow> families() {
		return this.jdbc.sql("""
				select f.id, f.display_name, f.family_key, f.review_status, f.updated_at, f.canonical_url,
				       (select v.value_text from attribute_value v
				         where v.revision_id = f.current_revision_id and v.variant_id is null and v.raw_label = 'Category'
				         limit 1) as category,
				       (select count(*) from product_variant pv where pv.family_id = f.id and not pv.excluded) as skus,
				       (select count(*) from product_asset_link l
				         where l.revision_id = f.current_revision_id and l.role_hint in ('MAIN_IMAGE', 'GALLERY_IMAGE', 'VARIANT_IMAGE')) as images,
				       (select count(*) from product_asset_link l
				         where l.revision_id = f.current_revision_id and l.role_hint in ('CERTIFICATE', 'DRAWING', 'DOCUMENT')) as documents,
				       (select count(*) from product_flag pf where pf.family_id = f.id and pf.status = 'OPEN') as flags,
				       (select s.url from product_asset_link l join asset_source s on s.id = l.asset_source_id
				         where l.revision_id = f.current_revision_id and l.role_hint = 'MAIN_IMAGE' limit 1) as main_image
				  from product_family f
				 order by category nulls last, f.display_name
				""")
			.query((rs, row) -> new FamilyRow(rs.getLong("id"), rs.getString("display_name"), rs.getString("family_key"),
					rs.getString("category"), rs.getInt("skus"), rs.getInt("images"), rs.getInt("documents"),
					rs.getInt("flags"), rs.getString("review_status"), rs.getString("main_image"),
					rs.getObject("updated_at", OffsetDateTime.class)))
			.list();
	}

	public Optional<FamilyDetail> family(long familyId) {
		Optional<FamilyHeader> header = this.jdbc.sql("""
				select f.id, f.display_name, f.family_key, f.canonical_url, f.review_status, f.locale,
				       r.id as revision_id, r.revision_number, r.created_at as extracted_at, r.extractor_version,
				       r.failed_sections::text as warnings, fr.final_url as source_url, fr.fetched_at
				  from product_family f
				  left join extraction_revision r on r.id = f.current_revision_id
				  left join fetched_resource fr on fr.id = r.source_resource_ids[1]
				 where f.id = :id
				""")
			.param("id", familyId)
			.query((rs, row) -> new FamilyHeader(rs.getLong("id"), rs.getString("display_name"),
					rs.getString("family_key"), rs.getString("canonical_url"), rs.getString("review_status"),
					rs.getString("locale"), rs.getLong("revision_id"), rs.getInt("revision_number"),
					rs.getObject("extracted_at", OffsetDateTime.class), rs.getString("extractor_version"),
					rs.getString("warnings"), rs.getString("source_url"), rs.getObject("fetched_at", OffsetDateTime.class)))
			.optional();
		return header.map((h) -> new FamilyDetail(h, values(h.revisionId(), true), variantMatrix(h.revisionId(), familyId),
				assets(h.revisionId()), flags(familyId)));
	}

	private List<ValueCell> values(long revisionId, boolean familyLevel) {
		return this.jdbc.sql("""
				select variant_id, raw_label, raw_value, source_type, source_locator, source_excerpt,
				       jsonb_array_length(corroborating_sources) as corroborations
				  from attribute_value
				 where revision_id = :revisionId and (variant_id is null) = :familyLevel
				 order by variant_id nulls first, position
				""")
			.param("revisionId", revisionId)
			.param("familyLevel", familyLevel)
			.query((rs, row) -> {
				long variant = rs.getLong("variant_id");
				return new ValueCell(rs.wasNull() ? null : variant, rs.getString("raw_label"), rs.getString("raw_value"),
						rs.getString("source_type"), rs.getString("source_locator"), rs.getString("source_excerpt"),
						rs.getInt("corroborations"));
			})
			.list();
	}

	/**
	 * Variants as rows, labels as columns in order of first appearance. A label with two different values for one
	 * variant (sources disagree) shows every value.
	 */
	private VariantMatrix variantMatrix(long revisionId, long familyId) {
		List<VariantRow> variants = this.jdbc.sql("""
				select id, sku_display, variant_label from product_variant
				 where family_id = :familyId and last_seen_revision_id = :revisionId
				 order by variant_label, sku_display
				""")
			.param("familyId", familyId)
			.param("revisionId", revisionId)
			.query((rs, row) -> new VariantRow(rs.getLong("id"), rs.getString("sku_display"),
					rs.getString("variant_label"), new LinkedHashMap<>()))
			.list();
		Map<Long, VariantRow> byId = new LinkedHashMap<>();
		variants.forEach((v) -> byId.put(v.id(), v));
		Set<String> columns = new LinkedHashSet<>();
		for (ValueCell cell : values(revisionId, false)) {
			VariantRow variant = byId.get(cell.variantId());
			if (variant != null) {
				columns.add(cell.label());
				variant.cells().computeIfAbsent(cell.label(), (label) -> new ArrayList<>()).add(cell);
			}
		}
		return new VariantMatrix(new ArrayList<>(columns), variants);
	}

	private List<AssetRow> assets(long revisionId) {
		return this.jdbc.sql("""
				select l.role_hint, l.displayed_title, s.url, s.status, v.sku_display
				  from product_asset_link l
				  join asset_source s on s.id = l.asset_source_id
				  left join product_variant v on v.id = l.variant_id
				 where l.revision_id = :revisionId
				 order by l.position
				""")
			.param("revisionId", revisionId)
			.query((rs, row) -> new AssetRow(rs.getString("role_hint"), rs.getString("displayed_title"),
					rs.getString("url"), rs.getString("status"), rs.getString("sku_display")))
			.list();
	}

	private List<FlagRow> flags(long familyId) {
		return this.jdbc.sql("""
				select flag_type, severity, message, target_key from product_flag
				 where family_id = :familyId and status = 'OPEN' order by severity, flag_type
				""")
			.param("familyId", familyId)
			.query((rs, row) -> new FlagRow(rs.getString("flag_type"), rs.getString("severity"), rs.getString("message"),
					rs.getString("target_key")))
			.list();
	}

	public record FamilyRow(long id, String name, String familyKey, String category, int skus, int images,
			int documents, int flags, String reviewStatus, String mainImage, OffsetDateTime updatedAt) {
	}

	public record FamilyHeader(long id, String name, String familyKey, String canonicalUrl, String reviewStatus,
			String locale, long revisionId, int revisionNumber, OffsetDateTime extractedAt, String extractorVersion,
			String warningsJson, String sourceUrl, OffsetDateTime fetchedAt) {
	}

	public record FamilyDetail(FamilyHeader header, List<ValueCell> familyValues, VariantMatrix variants,
			List<AssetRow> assets, List<FlagRow> flags) {

		public List<AssetRow> images() {
			return this.assets.stream().filter((a) -> a.role().endsWith("_IMAGE")).toList();
		}

		public List<AssetRow> documents() {
			return this.assets.stream().filter((a) -> !a.role().endsWith("_IMAGE")).toList();
		}

	}

	public record ValueCell(Long variantId, String label, String value, String source, String locator, String excerpt,
			int corroborations) {
	}

	public record VariantMatrix(List<String> columns, List<VariantRow> rows) {
	}

	public record VariantRow(long id, String sku, String label, Map<String, List<ValueCell>> cells) {
	}

	public record AssetRow(String role, String title, String url, String status, String variantSku) {
	}

	public record FlagRow(String type, String severity, String message, String target) {
	}

}
