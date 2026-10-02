package com.example.oemintel.adapter;

/**
 * OEM-specific knowledge behind one interface (architecture principle 2). A data-feed adapter would plug into the
 * same place as a website adapter.
 */
public interface OemAdapter {

	/** Matches oem.adapter_key. */
	String key();

	/** Raised whenever the extraction logic changes, so affected revisions can be found and re-extracted. */
	String extractorVersion();

	/** Whether the page is a product family page this adapter can extract, and if not, why. */
	PageCheck check(String html, String url);

	ExtractedFamily extract(String html, String url);

	/**
	 * @param canonicalUrl the page's canonical URL, used to detect the same family under two URLs
	 */
	record PageCheck(boolean isFamily, String reason, String canonicalUrl) {
	}

}
