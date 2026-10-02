package com.example.oemintel.engine;

import java.time.Duration;

/**
 * Every kind of work the engine can run. The lane, attempt budget and lease length are fixed per type,
 * so an item's retry behaviour never depends on which handler happens to be deployed.
 */
public enum TaskType {

	DISCOVER(Lane.LOCAL, 3, Duration.ofMinutes(1), true),
	FETCH_SITEMAP(Lane.NETWORK, 5, Duration.ofMinutes(2), true),
	FETCH_CATEGORY(Lane.NETWORK, 5, Duration.ofMinutes(2), true),
	FETCH_LISTING(Lane.NETWORK, 5, Duration.ofMinutes(2), true),
	FETCH_PAGE(Lane.NETWORK, 5, Duration.ofMinutes(2), false),
	EXTRACT_FAMILY(Lane.LOCAL, 2, Duration.ofMinutes(5), false),
	DOWNLOAD_ASSET(Lane.NETWORK, 5, Duration.ofMinutes(10), false),
	PARSE_DOCUMENT(Lane.LOCAL, 2, Duration.ofMinutes(10), false),
	LLM_EXTRACT(Lane.LLM, 3, Duration.ofMinutes(3), false),
	ASSEMBLE_FAMILY(Lane.LOCAL, 2, Duration.ofMinutes(2), false),
	NORMALIZE(Lane.LOCAL, 2, Duration.ofMinutes(2), false),
	PLAN_REPROCESS(Lane.LOCAL, 2, Duration.ofMinutes(5), false);

	private final Lane lane;

	private final int maxAttempts;

	private final Duration lease;

	private final boolean discovery;

	TaskType(Lane lane, int maxAttempts, Duration lease, boolean discovery) {
		this.lane = lane;
		this.maxAttempts = maxAttempts;
		this.lease = lease;
		this.discovery = discovery;
	}

	public Lane lane() {
		return this.lane;
	}

	public int maxAttempts() {
		return this.maxAttempts;
	}

	public Duration lease() {
		return this.lease;
	}

	/** Discovery tasks must all finish before candidates are admitted (AD-13). */
	public boolean isDiscovery() {
		return this.discovery;
	}

}
