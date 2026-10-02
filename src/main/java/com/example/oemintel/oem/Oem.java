package com.example.oemintel.oem;

public record Oem(long id, String code, String name, String baseUrl, String adapterKey, String defaultLocale,
		CrawlAuthStatus crawlAuthStatus, boolean active) {

	/** The legal gate (AD-30): recorded per OEM, enforced when jobs are created. */
	public enum CrawlAuthStatus {

		PENDING, AUTHORISED, DENIED

	}

}
