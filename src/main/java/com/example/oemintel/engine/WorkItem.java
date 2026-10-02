package com.example.oemintel.engine;

import java.util.UUID;

/**
 * A leased unit of work as the handler sees it. The lease token is the fencing token (AD-03): results
 * are only committed while the item is still leased under this exact token.
 */
public record WorkItem(long id, long jobId, TaskType taskType, String host, String target, String payload,
		String dedupeKey, Long parentId, int attempts, int maxAttempts, UUID leaseToken) {
}
