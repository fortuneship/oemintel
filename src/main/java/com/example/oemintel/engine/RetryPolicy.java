package com.example.oemintel.engine;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/** Turns a failed attempt into the item's next state (SDD 9.1 retry classes). */
@Component
public class RetryPolicy {

	/** Unexpected handler exceptions get at most this many attempts, whatever the task type allows. */
	static final int BUG_MAX_ATTEMPTS = 2;

	private final EngineProperties.Retry settings;

	public RetryPolicy(EngineProperties properties) {
		this.settings = properties.retry();
	}

	public Decision decide(WorkItem item, ErrorClass errorClass, Duration retryAfter) {
		return switch (errorClass) {
			case SKIPPED -> new Decision(ItemStatus.SKIPPED, Duration.ZERO, false);
			case PERMANENT -> new Decision(ItemStatus.DEAD, Duration.ZERO, false);
			case RATE_LIMITED -> new Decision(ItemStatus.RETRY_WAIT,
					(retryAfter != null) ? retryAfter : backoff(1), true);
			case HOST_BLOCKED -> new Decision(ItemStatus.RETRY_WAIT,
					(retryAfter != null) ? retryAfter : this.settings.hostBlockedDelay(), true);
			case TRANSIENT, LEASE_EXPIRED -> retryOrDie(item, item.maxAttempts());
			case BUG -> retryOrDie(item, Math.min(BUG_MAX_ATTEMPTS, item.maxAttempts()));
		};
	}

	private Decision retryOrDie(WorkItem item, int maxAttempts) {
		if (item.attempts() >= maxAttempts) {
			return new Decision(ItemStatus.DEAD, Duration.ZERO, false);
		}
		return new Decision(ItemStatus.RETRY_WAIT, backoff(item.attempts()), false);
	}

	/** Exponential backoff: base, 4x base, 16x base... capped, with jitter so retries don't arrive in waves. */
	Duration backoff(int attempt) {
		double factor = Math.pow(4, Math.max(0, attempt - 1));
		double millis = Math.min(this.settings.baseDelay().toMillis() * factor, this.settings.maxDelay().toMillis());
		double jitter = this.settings.jitter() * (2 * ThreadLocalRandom.current().nextDouble() - 1);
		return Duration.ofMillis(Math.max(0, Math.round(millis * (1 + jitter))));
	}

	/**
	 * @param refundAttempt whether the attempt counter is given back (rate limits and blocks are not the item's
	 * fault)
	 */
	public record Decision(ItemStatus status, Duration delay, boolean refundAttempt) {
	}

}
