package com.example.oemintel.job;

import org.springframework.http.HttpStatus;

/** A job request that cannot be honoured; carries the HTTP status the API should answer with. */
public class JobException extends RuntimeException {

	private final HttpStatus status;

	public JobException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public static JobException notFound(String message) {
		return new JobException(HttpStatus.NOT_FOUND, message);
	}

	public static JobException conflict(String message) {
		return new JobException(HttpStatus.CONFLICT, message);
	}

	public static JobException badRequest(String message) {
		return new JobException(HttpStatus.BAD_REQUEST, message);
	}

	public static JobException forbidden(String message) {
		return new JobException(HttpStatus.FORBIDDEN, message);
	}

	public HttpStatus status() {
		return this.status;
	}

}
