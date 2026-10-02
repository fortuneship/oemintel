package com.example.oemintel.crawl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Canonical form of a URL for deduplication. This is the minimal version needed by the job engine (scheme and
 * host lower-cased, default port and fragment removed); Step 4 extends it with query-parameter sorting, tracking
 * parameter removal and the adapter's trailing-slash rule.
 */
public final class UrlNormalizer {

	private UrlNormalizer() {
	}

	/** @return the normalised URL, or empty if it is not an absolute http(s) URL */
	public static Optional<String> normalize(String url) {
		if (url == null || url.isBlank()) {
			return Optional.empty();
		}
		try {
			URI uri = new URI(url.trim());
			String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
			if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
				return Optional.empty();
			}
			int port = uri.getPort();
			if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
				port = -1;
			}
			String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
			String normalized = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
					+ ((port != -1) ? ":" + port : "") + path
					+ ((uri.getRawQuery() != null) ? "?" + uri.getRawQuery() : "");
			return Optional.of(normalized);
		}
		catch (URISyntaxException ex) {
			return Optional.empty();
		}
	}

	public static String host(String url) {
		return URI.create(url).getHost().toLowerCase(Locale.ROOT);
	}

}
