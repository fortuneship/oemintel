package com.example.oemintel.engine;

/**
 * Executes one task type. Contract (SDD 9.1):
 * <ul>
 * <li>idempotent: the engine runs items at least once, so a repeat must be harmless;</li>
 * <li>no database transaction or connection held during I/O: persist through {@link TaskResult#onCommit()};</li>
 * <li>signal expected failures with {@link TaskFailure}.</li>
 * </ul>
 */
public interface TaskHandler {

	TaskType taskType();

	TaskResult handle(WorkItem item, TaskContext context) throws Exception;

}
