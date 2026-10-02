package com.example.oemintel.engine;

/**
 * Work to enqueue. The dedupe key makes enqueueing idempotent within a job: the same URL discovered twice
 * is queued once.
 *
 * @param payload JSON object with handler parameters, or {@code null} for none
 */
public record NewWorkItem(TaskType taskType, String target, String dedupeKey, String host, String payload,
		int priority) {

	public static NewWorkItem of(TaskType taskType, String target, String dedupeKey) {
		return new NewWorkItem(taskType, target, dedupeKey, null, null, 0);
	}

	public NewWorkItem withHost(String host) {
		return new NewWorkItem(this.taskType, this.target, this.dedupeKey, host, this.payload, this.priority);
	}

	public NewWorkItem withPayload(String payload) {
		return new NewWorkItem(this.taskType, this.target, this.dedupeKey, this.host, payload, this.priority);
	}

}
