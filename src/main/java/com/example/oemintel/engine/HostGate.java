package com.example.oemintel.engine;

import java.time.Duration;
import java.util.Set;

/**
 * Per-host politeness state consulted before NETWORK items are leased (AD-08): an item is only leased when
 * its host may be contacted, so leases never expire while waiting on a rate limit. Implemented by the polite
 * fetcher's {@code PoliteHostGate}.
 */
public interface HostGate {

	/** Hosts that must not be contacted right now (paused, blocked, backing off, outside the crawl window). */
	Set<String> hostsNotReady();

	void reportRateLimited(String host, Duration retryAfter);

	void reportBlocked(String host, String reason);

}
