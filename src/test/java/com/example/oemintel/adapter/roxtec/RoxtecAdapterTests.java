package com.example.oemintel.adapter.roxtec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.example.oemintel.adapter.ExtractedFamily;
import com.example.oemintel.adapter.ExtractedFamily.ExtractedVariant;
import com.example.oemintel.adapter.OemAdapter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test against a saved copy of the Roxtec GH frame page (fetched 2026-10-02, kept for internal testing
 * only). Expected values were read off the live page by hand.
 */
class RoxtecAdapterTests {

	private static final String URL = "https://www.roxtec.com/en/products/system-components/rectangular-frames/roxtec-gh-frame/";

	private static final RoxtecAdapter adapter = new RoxtecAdapter(JsonMapper.builder().build());

	private static String html;

	@BeforeAll
	static void loadFixture() throws IOException {
		try (InputStream in = RoxtecAdapterTests.class.getResourceAsStream("/fixtures/roxtec/roxtec-gh-frame.html")) {
			html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void recognisesAFamilyPageAndRejectsOtherPages() {
		OemAdapter.PageCheck family = adapter.check(html, URL);
		assertThat(family.isFamily()).isTrue();
		assertThat(family.canonicalUrl()).isEqualTo(URL);
		assertThat(adapter.check("<html><body><h1>About us</h1></body></html>", URL).isFamily()).isFalse();
	}

	@Test
	void extractsFamilyIdentityFromStructuredData() {
		ExtractedFamily family = adapter.extract(html, URL);
		assertThat(family.familyKey()).isEqualTo("roxtec-gh-frame");
		assertThat(family.name()).isEqualTo("Roxtec GH frame");
		assertThat(family.category()).isEqualTo("Rectangular frames");
		assertThat(family.description()).startsWith("The Roxtec GH frame with pre-drilled holes");
	}

	@Test
	void extractsEveryArticleWithoutInventingAny() {
		ExtractedFamily family = adapter.extract(html, URL);
		assertThat(family.variants()).hasSize(97);
		assertThat(family.variants()).extracting(ExtractedVariant::sku).doesNotHaveDuplicates();
		ExtractedVariant gh2x1 = variant(family, "5GH0000008110");
		assertThat(gh2x1.label()).isEqualTo("GH 2x1 GALV");
		assertThat(value(gh2x1, "Weight (kg)", "JSON_LD")).isEqualTo("3.18");
		assertThat(value(gh2x1, "Size", "JSON_LD")).isEqualTo("252.5 x 233 x 60");
	}

	@Test
	void readsSpecTablesByArticleNumberWithProvenance() {
		ExtractedVariant gh2x1 = variant(adapter.extract(html, URL), "5GH0000008110");
		assertThat(value(gh2x1, "Table group", "HTML_SELECTOR")).isEqualTo("Galvanized mild steel");
		assertThat(value(gh2x1, "Frame openings", "HTML_SELECTOR")).isEqualTo("1");
		assertThat(value(gh2x1, "Packing space per opening (mm)", "HTML_SELECTOR")).isEqualTo("120 x 60");
		assertThat(value(gh2x1, "Aperture dimensions WxH (mm)", "HTML_SELECTOR")).isEqualTo("148 x 128");
		assertThat(value(gh2x1, "Weight (kg)", "HTML_SELECTOR")).isEqualTo("3.18");
		assertThat(gh2x1.values()).filteredOn((v) -> v.source().equals("HTML_SELECTOR"))
			.allSatisfy((v) -> assertThat(v.locator()).contains("table[").contains("row"))
			.allSatisfy((v) -> assertThat(v.excerpt()).contains("5GH0000008110"));
	}

	@Test
	void findsImagesAndDocumentsButNoCadFiles() {
		ExtractedFamily family = adapter.extract(html, URL);
		assertThat(family.assets()).filteredOn((a) -> a.roleHint().equals("MAIN_IMAGE")).hasSize(1);
		assertThat(family.assets()).filteredOn((a) -> a.roleHint().equals("CERTIFICATE")).isNotEmpty();
		assertThat(family.assets()).filteredOn((a) -> a.roleHint().equals("DRAWING")).isNotEmpty();
		assertThat(family.assets()).extracting(ExtractedFamily.ExtractedAsset::url)
			.noneMatch((url) -> url.toLowerCase().endsWith(".dxf") || url.toLowerCase().endsWith(".stp"))
			.doesNotHaveDuplicates();
		assertThat(family.warnings()).anyMatch((w) -> w.contains("no article number column"));
	}

	private static ExtractedVariant variant(ExtractedFamily family, String sku) {
		return family.variants().stream().filter((v) -> v.sku().equals(sku)).findFirst().orElseThrow();
	}

	private static String value(ExtractedVariant variant, String label, String source) {
		List<String> values = variant.values()
			.stream()
			.filter((v) -> v.label().equals(label) && v.source().equals(source))
			.map(ExtractedFamily.ExtractedValue::value)
			.toList();
		assertThat(values).as("%s from %s", label, source).hasSize(1);
		return values.get(0);
	}

}
