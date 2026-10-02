package com.example.oemintel.engine;

import java.time.Duration;

/**
 * Thrown by a handler to tell the engine how to treat a failed attempt. Any other exception is
 * classified as {@link ErrorClass#BUG}.
 */
public class TaskFailure extends RuntimeException {

	private final ErrorClass errorClass;

	private final Duration retryAfter;

	public TaskFailure(ErrorClass errorClass, String message) {
		this(errorClass, message, null, null);
	}

	public TaskFailure(ErrorClass errorClass, String message, Duration retryAfter, Throwable cause) {
		super(message, cause);
		this.errorClass = errorClass;
		this.retryAfter = retryAfter;
	}

	public static TaskFailure transientFailure(String message, Throwable cause) {
		return new TaskFailure(ErrorClass.TRANSIENT, message, null, cause);
	}

	public static TaskFailure permanent(String message) {
		return new TaskFailure(ErrorClass.PERMANENT, message);
	}

	public static TaskFailure rateLimited(String message, Duration retryAfter) {
		return new TaskFailure(ErrorClass.RATE_LIMITED, message, retryAfter, null);
	}

	public static TaskFailure hostBlocked(String message) {
		return new TaskFailure(ErrorClass.HOST_BLOCKED, message);
	}

	public static TaskFailure skipped(String message) {
		return new TaskFailure(ErrorClass.SKIPPED, message);
	}

	public ErrorClass errorClass() {
		return this.errorClass;
	}

	public Duration retryAfter() {
		return this.retryAfter;
	}

}
