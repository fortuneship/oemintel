package com.example.oemintel.engine;

/** Services a handler may call while it works. */
public interface TaskContext {

	/**
	 * Extends the lease for long-running work (large PDFs).
	 * @return {@code false} if the lease was lost: the result will be discarded, so the handler may stop early
	 */
	boolean heartbeat();

}
