package com.example.oemintel.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one leased item: work happens outside any transaction; the result is committed in a single transaction
 * that first re-checks the lease token (AD-03, AD-04). A worker whose lease expired and was taken over can
 * therefore never overwrite the new owner's result.
 */
@Component
public class TaskRunner {

	private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

	private final HandlerRegistry registry;

	private final WorkItemRepository items;

	private final RetryPolicy retryPolicy;

	private final HostGate hostGate;

	private final TransactionTemplate tx;

	public TaskRunner(HandlerRegistry registry, WorkItemRepository items, RetryPolicy retryPolicy, HostGate hostGate,
			TransactionTemplate tx) {
		this.registry = registry;
		this.items = items;
		this.retryPolicy = retryPolicy;
		this.hostGate = hostGate;
		this.tx = tx;
	}

	public void run(WorkItem item) {
		MDC.put("jobId", Long.toString(item.jobId()));
		MDC.put("workItemId", Long.toString(item.id()));
		try {
			TaskHandler handler = this.registry.handlerFor(item.taskType())
				.orElseThrow(() -> new IllegalStateException("No handler for " + item.taskType()));
			TaskResult result;
			try {
				result = handler.handle(item, () -> this.items.extendLease(item.id(), item.leaseToken()));
			}
			catch (TaskFailure failure) {
				fail(item, failure.errorClass(), failure);
				return;
			}
			catch (Exception ex) {
				log.error("Handler {} failed unexpectedly on item {}", item.taskType(), item.id(), ex);
				fail(item, ErrorClass.BUG, ex);
				return;
			}
			complete(item, result);
		}
		finally {
			MDC.remove("jobId");
			MDC.remove("workItemId");
		}
	}

	private void complete(WorkItem item, TaskResult result) {
		try {
			this.tx.executeWithoutResult((status) -> {
				if (!this.items.markDone(item.id(), item.leaseToken())) {
					throw new LeaseLostException();
				}
				if (result.onCommit() != null) {
					result.onCommit().run();
				}
				for (NewWorkItem child : result.children()) {
					this.items.enqueue(item.jobId(), item.id(), child);
				}
			});
		}
		catch (LeaseLostException ex) {
			log.warn("Discarded result of item {}: its lease expired and it was taken over", item.id());
		}
		catch (RuntimeException ex) {
			// The commit itself failed (constraint violation, lost connection). Nothing was written; retry the item.
			log.error("Committing the result of item {} failed", item.id(), ex);
			fail(item, ErrorClass.BUG, ex);
		}
	}

	private void fail(WorkItem item, ErrorClass errorClass, Throwable cause) {
		TaskFailure failure = (cause instanceof TaskFailure taskFailure) ? taskFailure : null;
		if (errorClass == ErrorClass.RATE_LIMITED && item.host() != null) {
			this.hostGate.reportRateLimited(item.host(), (failure != null) ? failure.retryAfter() : null);
		}
		if (errorClass == ErrorClass.HOST_BLOCKED && item.host() != null) {
			this.hostGate.reportBlocked(item.host(), cause.getMessage());
		}
		RetryPolicy.Decision decision = this.retryPolicy.decide(item, errorClass,
				(failure != null) ? failure.retryAfter() : null);
		boolean applied = this.items.reschedule(item.id(), item.leaseToken(), decision.status(), decision.delay(),
				decision.refundAttempt(), errorClass, describe(cause));
		if (!applied) {
			log.warn("Could not record failure of item {}: lease lost", item.id());
		}
		else if (decision.status() == ItemStatus.DEAD) {
			log.warn("Item {} ({}) is dead after {} attempt(s): {}", item.id(), item.taskType(), item.attempts(),
					describe(cause));
		}
	}

	private static String describe(Throwable cause) {
		String message = cause.getMessage();
		return cause.getClass().getSimpleName() + ((message != null) ? ": " + message : "");
	}

	private static final class LeaseLostException extends RuntimeException {

		LeaseLostException() {
			super(null, null, false, false);
		}

	}

}
