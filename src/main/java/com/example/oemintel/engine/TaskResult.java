package com.example.oemintel.engine;

import java.util.List;

/**
 * What a handler produced. The engine commits it in one transaction together with marking the item done,
 * and only if the lease token still matches. Handlers never mark themselves done.
 *
 * @param children work to enqueue (skipped if the job was cancelled meanwhile)
 * @param onCommit writes to perform inside the completion transaction, or {@code null}
 */
public record TaskResult(List<NewWorkItem> children, CommitAction onCommit) {

	public static TaskResult done() {
		return new TaskResult(List.of(), null);
	}

	public static TaskResult withChildren(List<NewWorkItem> children) {
		return new TaskResult(children, null);
	}

	public static TaskResult committing(CommitAction onCommit) {
		return new TaskResult(List.of(), onCommit);
	}

	/** Database writes that must commit atomically with the item's completion. */
	@FunctionalInterface
	public interface CommitAction {

		void run();

	}

}
