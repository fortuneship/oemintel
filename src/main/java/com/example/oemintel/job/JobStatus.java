package com.example.oemintel.job;

import java.util.Set;

public enum JobStatus {

	CREATED, DISCOVERING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, FAILED;

	/** Statuses covered by the one-active-job-per-OEM rule (AD-06). */
	public static final Set<JobStatus> ACTIVE = Set.of(CREATED, DISCOVERING, RUNNING, PAUSED);

}
