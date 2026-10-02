package com.example.oemintel.engine;

/**
 * How a failed attempt is treated (SDD 9.1). Only TRANSIENT and BUG consume the attempt budget:
 * being told to slow down or being blocked is not the item's fault.
 */
public enum ErrorClass {

	/** Timeout, connection reset, 5xx: retried with exponential backoff. */
	TRANSIENT,
	/** 429 or Retry-After: rescheduled after the given delay, attempt refunded. */
	RATE_LIMITED,
	/** Challenge page or a run of 403s: the host is paused, attempt refunded. */
	HOST_BLOCKED,
	/** 404/410, unsupported content, not a product: never retried. */
	PERMANENT,
	/** Deliberately not processed (robots disallow, policy). */
	SKIPPED,
	/** Unexpected exception in a handler: at most two attempts. */
	BUG,
	/** Set by the reaper when a worker disappeared without completing. */
	LEASE_EXPIRED

}
