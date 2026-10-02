package com.example.oemintel.crawl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * robots.txt per host, following RFC 9309 via crawler-commons: a 4xx means everything is allowed, a 5xx or
 * network failure means nothing is allowed until a later fetch succeeds. The robots.txt request itself is paced
 * like any other request.
 */
@Component
public class RobotsPolicy {

	private static final Logger log = LoggerFactory.getLogger(RobotsPolicy.class);

	/** How long a failed robots.txt fetch keeps the host fully disallowed before trying again. */
	private static final Duration FAILURE_RETRY = Duration.ofMinutes(10);

	private final HttpClient http;

	private final PoliteHostGate gate;

	private final FetchProperties properties;

	private final JdbcClient jdbc;

	private final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();

	private final Map<String, CachedRules> cache = new ConcurrentHashMap<>();

	public RobotsPolicy(HttpClient crawlHttpClient, PoliteHostGate gate, FetchProperties properties, JdbcClient jdbc) {
		this.http = crawlHttpClient;
		this.gate = gate;
		this.properties = properties;
		this.jdbc = jdbc;
	}

	public boolean isAllowed(long oemId, String url) throws InterruptedException {
		return rules(oemId, URI.create(url)).isAllowed(url);
	}

	/** The robots.txt Crawl-delay, or zero if none is set. */
	public Duration crawlDelay(long oemId, String url) throws InterruptedException {
		long millis = rules(oemId, URI.create(url)).getCrawlDelay();
		return (millis > 0) ? Duration.ofMillis(millis) : Duration.ZERO;
	}

	private BaseRobotRules rules(long oemId, URI uri) throws InterruptedException {
		String host = uri.getHost().toLowerCase();
		CachedRules cached = this.cache.get(host);
		if (cached != null && cached.validUntil().isAfter(Instant.now())) {
			return cached.rules();
		}
		synchronized (this) {
			cached = this.cache.get(host);
			if (cached != null && cached.validUntil().isAfter(Instant.now())) {
				return cached.rules();
			}
			CachedRules loaded = loadStored(oemId, host).orElse(null);
			if (loaded == null) {
				loaded = fetch(oemId, uri.getScheme() + "://" + uri.getRawAuthority() + "/robots.txt", host);
			}
			this.cache.put(host, loaded);
			return loaded.rules();
		}
	}

	private java.util.Optional<CachedRules> loadStored(long oemId, String host) {
		return this.jdbc.sql("""
				select robots_txt, robots_fetched_at from crawl_host
				 where host = :host and robots_txt is not null
				   and robots_fetched_at > now() - make_interval(secs => :ttl)
				""")
			.param("host", host)
			.param("ttl", (double) this.properties.robotsTtl().toSeconds())
			.query((rs, row) -> {
				byte[] content = rs.getString("robots_txt").getBytes(StandardCharsets.UTF_8);
				Instant fetchedAt = rs.getObject("robots_fetched_at", OffsetDateTime.class).toInstant();
				return new CachedRules(parse("https://" + host + "/robots.txt", content),
						fetchedAt.plus(this.properties.robotsTtl()));
			})
			.optional();
	}

	private CachedRules fetch(long oemId, String robotsUrl, String host) throws InterruptedException {
		ensureHost(oemId, host);
		this.gate.acquire(host, Duration.ZERO);
		try {
			HttpResponse<byte[]> response = this.http.send(HttpRequest.newBuilder(URI.create(robotsUrl))
				.timeout(this.properties.requestTimeout())
				.header("User-Agent", this.properties.effectiveUserAgent())
				.GET()
				.build(), HttpResponse.BodyHandlers.ofByteArray());
			int status = response.statusCode();
			if (status >= 200 && status < 300) {
				byte[] body = response.body();
				BaseRobotRules rules = parse(robotsUrl, body);
				this.jdbc.sql("""
						update crawl_host set robots_txt = :robots, robots_fetched_at = now(), crawl_delay_ms = :delay,
						       updated_at = now()
						 where host = :host
						""")
					.param("robots", new String(body, StandardCharsets.UTF_8))
					.param("delay", (rules.getCrawlDelay() > 0) ? (int) rules.getCrawlDelay() : null)
					.param("host", host)
					.update();
				log.info("Loaded robots.txt for {}", host);
				return new CachedRules(rules, Instant.now().plus(this.properties.robotsTtl()));
			}
			log.warn("robots.txt for {} returned HTTP {}", host, status);
			Duration validFor = (status >= 500) ? FAILURE_RETRY : this.properties.robotsTtl();
			return new CachedRules(this.parser.failedFetch(status), Instant.now().plus(validFor));
		}
		catch (IOException ex) {
			log.warn("robots.txt for {} could not be fetched ({}); treating the host as fully disallowed", host,
					ex.getMessage());
			return new CachedRules(this.parser.failedFetch(503), Instant.now().plus(FAILURE_RETRY));
		}
	}

	private BaseRobotRules parse(String robotsUrl, byte[] content) {
		return this.parser.parseContent(robotsUrl, content, "text/plain", List.of(this.properties.robotsName()));
	}

	/** Every contacted host gets a crawl_host row: it holds robots.txt, pacing and block state. */
	void ensureHost(long oemId, String host) {
		this.jdbc.sql("""
				insert into crawl_host (host, oem_id, min_delay_ms, current_delay_ms)
				values (:host, :oemId, :delay, :delay)
				on conflict (host) do nothing
				""")
			.param("host", host)
			.param("oemId", oemId)
			.param("delay", (int) this.properties.defaultDelay().toMillis())
			.update();
	}

	private record CachedRules(BaseRobotRules rules, Instant validUntil) {
	}

}
