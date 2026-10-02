package com.example.oemintel.job;

import java.time.OffsetDateTime;

public record Job(long id, long oemId, JobMode mode, String locale, int limitValue, LimitUnit limitUnit,
		ExistingPolicy existingPolicy, String options, JobStatus status, String pauseReason, String createdBy,
		OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt) {

	public enum LimitUnit {

		FAMILY, SKU

	}

	/** Whether families that already exist are skipped (onboarding) or re-processed (refresh). */
	public enum ExistingPolicy {

		NEW_ONLY, REFRESH

	}

}
