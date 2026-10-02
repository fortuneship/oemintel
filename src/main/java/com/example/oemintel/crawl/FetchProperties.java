package com.example.oemintel.crawl;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Politeness and safety limits for all outgoing requests (NFR-01).
 *
 * @param contact optional contact (URL or email) appended to the User-Agent so site owners can reach the operator
 * instead of blocking; leave empty until the owner decides what to publish
 */
@ConfigurationProperties("oemintel.fetch")
public record FetchProperties(@DefaultValue("OEMIntelBot/0.1") String userAgent, String contact,
		@DefaultValue("oemintelbot") String robotsName, @DefaultValue("3s") Duration defaultDelay,
		@DefaultValue("0.2") double jitter, @DefaultValue("60s") Duration maxDelay,
		@DefaultValue("10s") Duration connectTimeout, @DefaultValue("30s") Duration requestTimeout,
		@DefaultValue("10000000") int maxPageBytes, @DefaultValue("5") int maxRedirects,
		@DefaultValue("7d") Duration freshness, @DefaultValue("24h") Duration robotsTtl) {

	public String effectiveUserAgent() {
		return (this.contact == null || this.contact.isBlank()) ? this.userAgent
				: this.userAgent + " (+" + this.contact + ")";
	}

}
