package com.example.oemintel.adapter.roxtec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

import com.example.oemintel.adapter.ExtractedFamily;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedAsset;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedValue;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedVariant;
import com.example.oemintel.adapter.OemAdapter;

/**
 * Roxtec family pages (observed 2026-10-02 on www.roxtec.com/en/products/system-components/...): server-rendered
 * HTML with a schema.org ProductGroup in JSON-LD listing every article ({@code hasVariant}), per-material spec
 * tables keyed by an "Art. No" column, drawing and certificate PDFs on cdn.content-publisher.roxtec.com.
 * <p>
 * Layer order follows AD-14: structured data first, HTML tables second. A value found in both is kept once with the
 * other source as corroboration later; differing values are both kept so a reviewer sees the conflict.
 */
@Component
public class RoxtecAdapter implements OemAdapter {

	static final String VERSION = "roxtec-0.1";

	private final JsonMapper json;

	public RoxtecAdapter(JsonMapper json) {
		this.json = json;
	}

	@Override
	public String key() {
		return "roxtec";
	}

	@Override
	public String extractorVersion() {
		return VERSION;
	}

	@Override
	public PageCheck check(String html, String url) {
		Document doc = Jsoup.parse(html, url);
		String canonical = canonical(doc).orElse(url);
		if (productGroup(doc).isEmpty()) {
			return new PageCheck(false, "No ProductGroup structured data: not a product family page", canonical);
		}
		return new PageCheck(true, null, canonical);
	}

	@Override
	public ExtractedFamily extract(String html, String url) {
		Document doc = Jsoup.parse(html, url);
		JsonNode group = productGroup(doc).orElseThrow(() -> new IllegalArgumentException("Not a family page: " + url));
		String canonical = canonical(doc).orElse(url);
		List<String> warnings = new ArrayList<>();

		String name = text(group, "name").orElseGet(() -> doc.select("h1").text());
		String familyKey = text(group, "productGroupID").orElseGet(() -> lastPathSegment(canonical));
		String description = text(group, "description").orElse(null);
		String category = text(group, "category").orElse(null);
		List<String> breadcrumb = breadcrumb(doc);

		List<ExtractedValue> familyValues = new ArrayList<>();
		if (category != null) {
			familyValues.add(new ExtractedValue("Category", category, "JSON_LD", "$.category", category));
		}

		Map<String, VariantBuilder> variants = new LinkedHashMap<>();
		List<ExtractedAsset> assets = new ArrayList<>();
		for (String image : strings(group.get("image"))) {
			assets.add(new ExtractedAsset(image, "IMAGE", assets.isEmpty() ? "MAIN_IMAGE" : "GALLERY_IMAGE", name, null));
		}
		readJsonLdVariants(group, variants, assets);
		readSpecTables(doc, variants, warnings);
		readDocuments(doc, assets);
		if (variants.isEmpty()) {
			warnings.add("No articles (SKUs) found on the page");
		}

		List<ExtractedVariant> variantList = variants.values().stream().map(VariantBuilder::build).toList();
		return new ExtractedFamily(familyKey, canonical, name, description, category, breadcrumb, familyValues,
				variantList, dedupe(assets), warnings);
	}

	private void readJsonLdVariants(JsonNode group, Map<String, VariantBuilder> variants, List<ExtractedAsset> assets) {
		JsonNode hasVariant = group.get("hasVariant");
		if (hasVariant == null || !hasVariant.isArray()) {
			return;
		}
		int index = 0;
		for (JsonNode variant : hasVariant) {
			String path = "$.hasVariant[" + index++ + "]";
			Optional<String> sku = text(variant, "sku");
			if (sku.isEmpty()) {
				continue;
			}
			String label = text(variant, "mpn").or(() -> text(variant, "name")).orElse(sku.get());
			VariantBuilder builder = variants.computeIfAbsent(normalizeSku(sku.get()),
					(key) -> new VariantBuilder(sku.get(), label, text(variant, "url").orElse(null)));
			JsonNode weight = variant.get("weight");
			if (weight != null && text(weight, "value").isPresent()) {
				String unit = unitLabel(text(weight, "unitCode").orElse(""));
				builder.values.add(new ExtractedValue("Weight" + (unit.isEmpty() ? "" : " (" + unit + ")"),
						text(weight, "value").get(), "JSON_LD", path + ".weight", weight.toString()));
			}
			JsonNode properties = variant.get("additionalProperty");
			if (properties != null && properties.isArray()) {
				int p = 0;
				for (JsonNode property : properties) {
					Optional<String> propertyName = text(property, "name");
					Optional<String> value = text(property, "value");
					if (propertyName.isPresent() && value.isPresent()) {
						builder.values.add(new ExtractedValue(propertyName.get(), value.get(), "JSON_LD",
								path + ".additionalProperty[" + p + "]", property.toString()));
					}
					p++;
				}
			}
			for (String image : strings(variant.get("image"))) {
				assets.add(new ExtractedAsset(image, "IMAGE", "VARIANT_IMAGE", label, sku.get()));
			}
		}
	}

	/** Tables with an "Art. No" column hold one row per article; other tables are reported, not guessed at. */
	private void readSpecTables(Document doc, Map<String, VariantBuilder> variants, List<String> warnings) {
		int tableIndex = 0;
		for (Element table : doc.select("table")) {
			tableIndex++;
			List<String> headers = table.select("thead th").eachText();
			int skuColumn = indexOfArticleNumber(headers);
			if (skuColumn < 0) {
				String caption = table.select("caption").text();
				warnings.add("Table %d%s has no article number column and was not extracted"
					.formatted(tableIndex, caption.isBlank() ? "" : " (" + caption + ")"));
				continue;
			}
			String caption = table.select("caption").text();
			int rowIndex = 0;
			for (Element row : table.select("tbody tr")) {
				rowIndex++;
				List<String> cells = row.select("td, th").eachText();
				if (cells.size() <= skuColumn || cells.get(skuColumn).isBlank()) {
					continue;
				}
				String sku = cells.get(skuColumn).trim();
				int nameColumn = headers.indexOf("Name");
				String label = (nameColumn >= 0 && nameColumn < cells.size()) ? cells.get(nameColumn) : sku;
				VariantBuilder builder = variants.computeIfAbsent(normalizeSku(sku),
						(key) -> new VariantBuilder(sku, label, null));
				String excerpt = String.join(" | ", cells);
				String locator = "table[%d]%s row %d".formatted(tableIndex,
						caption.isBlank() ? "" : " \"" + caption + "\"", rowIndex);
				if (!caption.isBlank()) {
					builder.values.add(new ExtractedValue("Table group", caption, "HTML_SELECTOR", locator + " caption",
							excerpt));
				}
				for (int column = 0; column < headers.size() && column < cells.size(); column++) {
					if (column == skuColumn || column == nameColumn || cells.get(column).isBlank()) {
						continue;
					}
					builder.values.add(new ExtractedValue(headers.get(column), cells.get(column), "HTML_SELECTOR",
							locator + " column \"" + headers.get(column) + "\"", excerpt));
				}
			}
		}
	}

	/**
	 * PDFs only: CAD formats (.dxf, .stp) are disallowed by Roxtec's robots.txt and out of scope by default.
	 * Certificate links sit inside {@code <script type="text/template">} modal templates rendered client-side; they
	 * are still in the raw HTML, so each template is parsed as an HTML fragment and titled with its modal title.
	 */
	private void readDocuments(Document doc, List<ExtractedAsset> assets) {
		readDocumentLinks(doc, null, assets);
		for (Element template : doc.select("script[type=text/template]")) {
			Document fragment = Jsoup.parseBodyFragment(template.data(), doc.location());
			readDocumentLinks(fragment, template.attr("data-modal-title"), assets);
		}
	}

	private void readDocumentLinks(Document doc, String contextTitle, List<ExtractedAsset> assets) {
		for (Element link : doc.select("a[href]")) {
			String href = link.absUrl("href").replace(" ", "%20");
			String path = href.toLowerCase(Locale.ROOT).split("\\?")[0];
			if (!path.endsWith(".pdf")) {
				continue;
			}
			String title = firstNonBlank(contextTitle, link.select(".document-list-item__left").text(),
					link.select("[class*=document-name], [class*=document__name], [class*=title]").text(), link.text(),
					lastPathSegment(href));
			String role = path.contains("/certificates/") ? "CERTIFICATE"
					: path.contains("/drawings/") ? "DRAWING" : "DOCUMENT";
			assets.add(new ExtractedAsset(href, "DOCUMENT", role, title, null));
		}
	}

	private Optional<JsonNode> productGroup(Document doc) {
		for (Element script : doc.select("script[type=application/ld+json]")) {
			JsonNode root;
			try {
				root = this.json.readTree(script.data());
			}
			catch (RuntimeException ex) {
				continue;
			}
			for (JsonNode node : root.isArray() ? root : List.of(root)) {
				if ("ProductGroup".equals(node.path("@type").asString(""))) {
					return Optional.of(node);
				}
			}
		}
		return Optional.empty();
	}

	private List<String> breadcrumb(Document doc) {
		for (Element script : doc.select("script[type=application/ld+json]")) {
			try {
				JsonNode root = this.json.readTree(script.data());
				for (JsonNode node : root.isArray() ? root : List.of(root)) {
					if ("BreadcrumbList".equals(node.path("@type").asString(""))) {
						List<String> names = new ArrayList<>();
						for (JsonNode item : node.path("itemListElement")) {
							text(item, "name").or(() -> text(item.path("item"), "name")).ifPresent(names::add);
						}
						return names;
					}
				}
			}
			catch (RuntimeException ex) {
				// Malformed block: ignore it, breadcrumbs are optional.
			}
		}
		return List.of();
	}

	private static Optional<String> canonical(Document doc) {
		Element link = doc.selectFirst("link[rel=canonical]");
		return Optional.ofNullable(link).map((l) -> l.absUrl("href")).filter((href) -> !href.isBlank());
	}

	private static int indexOfArticleNumber(List<String> headers) {
		for (int i = 0; i < headers.size(); i++) {
			String header = headers.get(i).toLowerCase(Locale.ROOT).replace(" ", "");
			if (header.startsWith("art.no") || header.startsWith("artno") || header.startsWith("articlenumber")
					|| header.startsWith("art.number")) {
				return i;
			}
		}
		return -1;
	}

	/** Article numbers compared without spaces, dots, dashes or case: "RM 20" and "rm20" are the same article. */
	public static String normalizeSku(String sku) {
		return sku.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
	}

	private static String unitLabel(String unitCode) {
		return switch (unitCode) {
			case "KGM" -> "kg";
			case "GRM" -> "g";
			case "MMT" -> "mm";
			default -> unitCode;
		};
	}

	private static Optional<String> text(JsonNode node, String field) {
		JsonNode value = (node != null) ? node.get(field) : null;
		if (value == null || value.isNull()) {
			return Optional.empty();
		}
		String text = value.isValueNode() ? value.asString().trim() : null;
		return (text == null || text.isEmpty()) ? Optional.empty() : Optional.of(text);
	}

	private static List<String> strings(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (node.isArray()) {
			List<String> values = new ArrayList<>();
			node.forEach((item) -> {
				if (item.isString() && !item.asString().isBlank()) {
					values.add(item.asString());
				}
			});
			return values;
		}
		return node.isString() ? List.of(node.asString()) : List.of();
	}

	private static List<ExtractedAsset> dedupe(List<ExtractedAsset> assets) {
		Set<String> seen = new LinkedHashSet<>();
		return assets.stream().filter((asset) -> seen.add(asset.url())).toList();
	}

	private static String lastPathSegment(String url) {
		String trimmed = url.split("\\?")[0].replaceAll("/+$", "");
		return trimmed.substring(trimmed.lastIndexOf('/') + 1);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private static final class VariantBuilder {

		final String sku;

		final String label;

		final String url;

		final List<ExtractedValue> values = new ArrayList<>();

		VariantBuilder(String sku, String label, String url) {
			this.sku = sku;
			this.label = label;
			this.url = url;
		}

		ExtractedVariant build() {
			return new ExtractedVariant(this.sku, this.label, this.url, List.copyOf(this.values));
		}

	}

}
