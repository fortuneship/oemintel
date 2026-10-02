package com.example.oemintel.web;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.oemintel.job.JobException;

/** Turns domain refusals into RFC 9457 problem responses with the right status instead of a 500. */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail handle(JobException ex) {
		return ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
	}

}
