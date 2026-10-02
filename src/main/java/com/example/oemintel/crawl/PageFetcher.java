package com.example.oemintel.crawl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

import com.example.oemintel.engine.TaskFailure;
import com.example.oemintel.storage.BlobStore;

/**
 * The only way the system talks to OEM websites (FR-06): scope allowlist, robots.txt on every hop, per-host pacing,
 * freshness reuse, size caps, raw storage, and a classification of every response that maps onto the engine's retry
 * classes. Failures are thrown as {@link TaskFailure} so the work item is retried, rescheduled or killed correctly.
 */
@Component
public class PageFetcher {

	/** Markers of bot-protection challenge pages. Deliberately specific: product pages may embed CAPTCHA forms. */
	private static final List<String> CHALLENGE_MARKERS = List.of("challenge-platform", "<title>Just a moment...</title>",
			"Attention Required! | Cloudflare", "cf-chl-");

	private final HttpClient http;

	private final PoliteHostGate gate;

	private final RobotsPolicy robots;

	private final FetchedResourceRepository resources;

	private final BlobStore blobs;

	private final FetchProperties properties;

	private final JsonMapper json;

	public PageFetcher(HttpClient crawlHttpClient, PoliteHostGate gate, RobotsPolicy robots,
			FetchedResourceRepository resources, BlobStore blobs, FetchProperties properties, JsonMapper json) {
		this.http = crawlHttpClient;
		this.gate = gate;
		this.robots = robots;
		this.resources = resources;
		this.blobs = blobs;
		this.properties = properties;
		this.json = json;
	}

	/**
	 * Fetches a page, or returns the stored copy if it was fetched successfully within the freshness window.
	 * @param allowedHosts the OEM's scope allowlist
	 */
	public FetchedPage fetch(long oemId, Long jobId, String url, Set<String> allowedHosts) throws InterruptedException {
		String normalized = UrlNormalizer.normalize(url).orElseThrow(() -> TaskFailure.permanent("Not a valid URL: " + url));
		checkScope(normalized, allowedHosts);
		this.robots.ensureHost(oemId, UrlNormalizer.host(normalized));
		if (!this.robots.isAllowed(oemId, normalized)) {
			throw TaskFailure.skipped("Disallowed by robots.txt: " + normalized);
		}
		Optional<FetchedResourceRepository.StoredResource> fresh = this.resources.findFresh(normalized,
				this.properties.freshness());
		if (fresh.isPresent()) {
			FetchedResourceRepository.StoredResource stored = fresh.get();
			return new FetchedPage(stored.id(), stored.finalUrl(), this.blobs.get(stored.storageKey()), true);
		}
		return download(oemId, jobId, url, normalized, allowedHosts);
	}

	private FetchedPage download(long oemId, Long jobId, String requestedUrl, String normalized,
			Set<String> allowedHosts) throws InterruptedException {
		List<String> chain = new ArrayList<>();
		String current = normalized;
		long started = System.nanoTime();
		for (int hop = 0; hop <= this.properties.maxRedirects(); hop++) {
			String host = UrlNormalizer.host(current);
			this.gate.acquire(host, this.robots.crawlDelay(oemId, current));
			HttpResponse<InputStream> response = send(current);
			int status = response.statusCode();
			if (status >= 300 && status < 400 && response.headers().firstValue("Location").isPresent()) {
				closeQuietly(response);
				chain.add(current);
				String next = URI.create(current).resolve(response.headers().firstValue("Location").get()).toString();
				current = UrlNormalizer.normalize(next)
					.orElseThrow(() -> TaskFailure.permanent("Redirect to an invalid URL: " + next));
				checkScope(current, allowedHosts);
				if (!this.robots.isAllowed(oemId, current)) {
					throw TaskFailure.skipped("Redirected to a URL disallowed by robots.txt: " + current);
				}
				continue;
			}
			byte[] body = readBody(response);
			int durationMs = (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
			String classification = classify(status, body);
			BlobStore.StoredBlob blob = (body.length > 0) ? this.blobs.put("raw", body) : null;
			long resourceId = this.resources.insert(new FetchedResourceRepository.NewResource(oemId, jobId, requestedUrl,
					normalized, current, status, response.headers().firstValue("Content-Type").orElse(null),
					(blob != null) ? blob.sha256() : null, (blob != null) ? blob.key() : null, classification,
					this.json.writeValueAsString(chain), response.headers().firstValue("ETag").orElse(null),
					response.headers().firstValue("Last-Modified").orElse(null), (long) body.length, durationMs));
			return outcome(host, status, classification, response, resourceId, current, body);
		}
		throw TaskFailure.permanent("More than %d redirects from %s".formatted(this.properties.maxRedirects(), normalized));
	}

	private FetchedPage outcome(String host, int status, String classification, HttpResponse<InputStream> response,
			long resourceId, String finalUrl, byte[] body) {
		switch (classification) {
			case "OK" -> {
				this.gate.recordSuccess(host);
				return new FetchedPage(resourceId, finalUrl, body, false);
			}
			case "NOT_FOUND" -> throw TaskFailure.permanent("HTTP " + status + " for " + finalUrl);
			case "RATE_LIMITED" -> throw TaskFailure.rateLimited("HTTP " + status + " from " + host, retryAfter(response));
			case "BLOCKED", "CHALLENGE" -> throw TaskFailure.hostBlocked(classification + " (HTTP " + status + ")");
			case "SERVER_ERROR" -> throw TaskFailure.transientFailure("HTTP " + status + " for " + finalUrl, null);
			default -> throw TaskFailure.permanent("Unexpected HTTP " + status + " for " + finalUrl);
		}
	}

	static String classify(int status, byte[] body) {
		boolean challenge = (status == 200 || status == 403 || status == 503) && looksLikeChallenge(body);
		if (challenge) {
			return "CHALLENGE";
		}
		if (status >= 200 && status < 300) {
			return "OK";
		}
		return switch (status) {
			case 404, 410 -> "NOT_FOUND";
			case 429 -> "RATE_LIMITED";
			case 403 -> "BLOCKED";
			default -> (status >= 500) ? "SERVER_ERROR" : "UNEXPECTED";
		};
	}

	private static boolean looksLikeChallenge(byte[] body) {
		String head = new String(body, 0, Math.min(body.length, 20_000), StandardCharsets.UTF_8);
		return CHALLENGE_MARKERS.stream().anyMatch(head::contains);
	}

	private HttpResponse<InputStream> send(String url) throws InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(this.properties.requestTimeout())
			.header("User-Agent", this.properties.effectiveUserAgent())
			.header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
			// java.net.http does not decompress responses: ask for gzip only and decode it in readBody.
			.header("Accept-Encoding", "gzip")
			.GET()
			.build();
		try {
			return this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (HttpTimeoutException ex) {
			throw TaskFailure.transientFailure("Timed out fetching " + url, ex);
		}
		catch (IOException ex) {
			throw TaskFailure.transientFailure("Network error fetching " + url + ": " + ex.getMessage(), ex);
		}
	}

	/** Reads at most the configured cap; Content-Length is never trusted. */
	private byte[] readBody(HttpResponse<InputStream> response) {
		boolean gzip = response.headers()
			.firstValue("Content-Encoding")
			.map((encoding) -> encoding.toLowerCase(Locale.ROOT).contains("gzip"))
			.orElse(false);
		int cap = this.properties.maxPageBytes();
		try (InputStream raw = response.body(); InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > cap) {
					throw TaskFailure.permanent("Response larger than %d bytes".formatted(cap));
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw TaskFailure.transientFailure("Reading the response failed: " + ex.getMessage(), ex);
		}
	}

	private static Duration retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("Retry-After").flatMap((value) -> {
			try {
				return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
			}
			catch (NumberFormatException ex) {
				return Optional.empty();
			}
		}).orElse(null);
	}

	private static void checkScope(String normalizedUrl, Set<String> allowedHosts) {
		String host = UrlNormalizer.host(normalizedUrl);
		if (!allowedHosts.contains(host)) {
			throw TaskFailure.skipped("Outside the OEM's allowed hosts: " + host);
		}
	}

	private static void closeQuietly(HttpResponse<InputStream> response) {
		try {
			response.body().close();
		}
		catch (IOException ex) {
			// Nothing useful to do; the connection is discarded.
		}
	}

	/**
	 * @param fromCache whether the stored copy was reused instead of contacting the site
	 */
	public record FetchedPage(long resourceId, String finalUrl, byte[] body, boolean fromCache) {

		public String text() {
			return new String(this.body, StandardCharsets.UTF_8);
		}

	}

}
