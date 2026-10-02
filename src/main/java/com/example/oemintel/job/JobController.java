package com.example.oemintel.job;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal JSON API over {@link JobService} (FR-24), the entry point for the Phase 2 chat interface. Until
 * authentication arrives with the dashboard (Step 11) every action is recorded under the actor "api".
 */
@RestController
@RequestMapping("/api/jobs")
class JobController {

	private static final String ACTOR = "api";

	private final JobService jobs;

	private final JobProgressQuery progress;

	JobController(JobService jobs, JobProgressQuery progress) {
		this.jobs = jobs;
		this.progress = progress;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Job create(@Valid @RequestBody CreateJobRequest request) {
		return this.jobs.create(request, ACTOR);
	}

	@GetMapping("/{id}")
	JobView get(@PathVariable long id) {
		Job job = this.jobs.find(id).orElseThrow(() -> JobException.notFound("No job " + id));
		return new JobView(job, this.progress.progress(id));
	}

	@PostMapping("/{id}/pause")
	void pause(@PathVariable long id, @RequestParam(required = false) String reason) {
		this.jobs.pause(id, reason, ACTOR);
	}

	@PostMapping("/{id}/resume")
	void resume(@PathVariable long id) {
		this.jobs.resume(id, ACTOR);
	}

	@PostMapping("/{id}/cancel")
	void cancel(@PathVariable long id) {
		this.jobs.cancel(id, ACTOR);
	}

	@PostMapping("/{id}/retry-dead")
	RetryResult retryDead(@PathVariable long id) {
		return new RetryResult(this.jobs.retryDead(id, ACTOR));
	}

	record JobView(Job job, JobProgressQuery.JobProgress progress) {
	}

	record RetryResult(int retried) {
	}

}
