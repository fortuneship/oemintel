package com.example.oemintel.adapter;

import java.util.List;

/**
 * Everything deterministic extraction found on one family page, with provenance on every value. Produced by a pure
 * function over stored raw HTML (AD-14), so re-extraction never needs the network.
 *
 * @param warnings things the extractor saw but could not use, shown to reviewers
 */
public record ExtractedFamily(String familyKey, String canonicalUrl, String name, String description, String category,
		List<String> breadcrumb, List<ExtractedValue> familyValues, List<ExtractedVariant> variants,
		List<ExtractedAsset> assets, List<String> warnings) {

	public record ExtractedVariant(String sku, String label, String url, List<ExtractedValue> values) {
	}

	/**
	 * @param source JSON_LD or HTML_SELECTOR
	 * @param locator where on the page the value came from (JSON path or table position)
	 * @param excerpt the surrounding source text a reviewer can check the value against
	 */
	public record ExtractedValue(String label, String value, String source, String locator, String excerpt) {
	}

	/**
	 * @param kind IMAGE or DOCUMENT
	 * @param roleHint MAIN_IMAGE, VARIANT_IMAGE, GALLERY_IMAGE, CERTIFICATE, DRAWING, DOCUMENT
	 * @param variantSku the variant the asset belongs to, or {@code null} for the whole family
	 */
	public record ExtractedAsset(String url, String kind, String roleHint, String title, String variantSku) {
	}

}
