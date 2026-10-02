package com.example.oemintel.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.oemintel.crawl.UrlNormalizer;
import com.example.oemintel.engine.NewWorkItem;
import com.example.oemintel.engine.TaskType;
import com.example.oemintel.engine.WorkItemRepository;
import com.example.oemintel.oem.Oem;
import com.example.oemintel.oem.OemRepository;

/** The single entry point for creating and controlling jobs, used by the dashboard and the JSON API alike. */
@Service
public class JobService {

	/**
	 * Largest run allowed while the OEM's crawl authorisation is still PENDING (SDD 12.5): enough for the golden
	 * set, far from a catalogue crawl.
	 */
	public static final int UNAUTHORISED_FAMILY_LIMIT = 20;

	private final OemRepository oems;

	private final JobRepository jobs;

	private final CandidateRepository candidates;

	private final WorkItemRepository items;

	private final JobEventRepository events;

	private final JsonMapper json;

	public JobService(OemRepository oems, JobRepository jobs, CandidateRepository candidates, WorkItemRepository items,
			JobEventRepository events, JsonMapper json) {
		this.oems = oems;
		this.jobs = jobs;
		this.candidates = candidates;
		this.items = items;
		this.events = events;
		this.json = json;
	}

	@Transactional
	public Job create(CreateJobRequest request, String actor) {
		Oem oem = this.oems.findByCode(request.oemCode())
			.orElseThrow(() -> JobException.notFound("Unknown OEM " + request.oemCode()));
		if (!oem.active()) {
			throw JobException.conflict("OEM %s is not active".formatted(oem.code()));
		}
		checkCrawlAuthorisation(oem, request);
		List<String> seedUrls = validateSeedUrls(request);
		String locale = (request.locale() != null && !request.locale().isBlank()) ? request.locale()
				: oem.defaultLocale();
		JobStatus initialStatus = (request.mode().networkAccess()) ? JobStatus.DISCOVERING : JobStatus.RUNNING;
		String options = this.json.writeValueAsString((request.options() != null) ? request.options() : Map.of());
		this.jobs.findActiveJobId(oem.id()).ifPresent((activeId) -> {
			throw JobException.conflict("OEM %s already has an active job (%d); finish, cancel or wait for it first"
				.formatted(oem.code(), activeId));
		});
		long jobId;
		try {
			jobId = this.jobs.insert(oem.id(), request.mode(), locale, request.limitValue(),
					request.limitUnitOrDefault(), request.existingPolicyOrDefault(), options, initialStatus, actor);
		}
		catch (DuplicateKeyException ex) {
			// Lost a race with a concurrent create; the unique index is the real guarantee (AD-06).
			throw JobException.conflict("OEM %s already has an active job".formatted(oem.code()));
		}
		seed(jobId, oem, request.mode(), seedUrls);
		this.events.info(jobId, "CREATED", "%s job created: limit %d %s, locale %s".formatted(request.mode(),
				request.limitValue(), request.limitUnitOrDefault(), locale), actor);
		return this.jobs.findById(jobId).orElseThrow();
	}

	/** The legal gate (AD-30), enforced here so it cannot be skipped by any client. */
	private void checkCrawlAuthorisation(Oem oem, CreateJobRequest request) {
		if (!request.mode().networkAccess()) {
			return;
		}
		switch (oem.crawlAuthStatus()) {
			case AUTHORISED -> {
			}
			case DENIED -> throw JobException.forbidden(
					"Crawling %s is recorded as DENIED; no network job can be created".formatted(oem.code()));
			case PENDING -> {
				boolean smallRun = request.limitUnitOrDefault() == Job.LimitUnit.FAMILY
						&& request.limitValue() <= UNAUTHORISED_FAMILY_LIMIT;
				if (request.mode() != JobMode.DRY_RUN && !smallRun) {
					throw JobException.forbidden(("Crawl authorisation for %s is PENDING. Record the legal decision "
							+ "first, or run a dry run or a capped run of at most %d families.")
						.formatted(oem.code(), UNAUTHORISED_FAMILY_LIMIT));
				}
			}
		}
	}

	private List<String> validateSeedUrls(CreateJobRequest request) {
		if (request.mode() != JobMode.SEED_LIST) {
			return List.of();
		}
		if (request.seedUrls() == null || request.seedUrls().isEmpty()) {
			throw JobException.badRequest("A SEED_LIST job needs at least one seed URL");
		}
		List<String> invalid = request.seedUrls()
			.stream()
			.filter((url) -> UrlNormalizer.normalize(url).isEmpty())
			.toList();
		if (!invalid.isEmpty()) {
			throw JobException.badRequest("Invalid seed URL(s): " + String.join(", ", invalid));
		}
		return request.seedUrls();
	}

	private void seed(long jobId, Oem oem, JobMode mode, List<String> seedUrls) {
		if (mode.discovers()) {
			this.items.enqueue(jobId, null, NewWorkItem.of(TaskType.DISCOVER, oem.baseUrl(), "seed"));
		}
		else if (mode == JobMode.SEED_LIST) {
			// Candidates are known up front; with no discovery work the evaluator moves straight to admission.
			for (int i = 0; i < seedUrls.size(); i++) {
				String url = seedUrls.get(i).trim();
				String normalized = UrlNormalizer.normalize(url).orElseThrow();
				this.candidates.upsert(jobId, url, normalized, "%06d".formatted(i), "SEED");
			}
		}
		else {
			this.items.enqueue(jobId, null, NewWorkItem.of(TaskType.PLAN_REPROCESS, mode.name(), "plan"));
		}
	}

	@Transactional
	public void pause(long jobId, String reason, String actor) {
		requireJob(jobId);
		if (!this.jobs.pause(jobId, reason)) {
			throw JobException.conflict("Only a discovering or running job can be paused");
		}
		this.events.info(jobId, "PAUSED", "Paused" + ((reason != null) ? ": " + reason : ""), actor);
	}

	@Transactional
	public void resume(long jobId, String actor) {
		requireJob(jobId);
		if (!this.jobs.resume(jobId)) {
			throw JobException.conflict("Only a paused job can be resumed");
		}
		this.events.info(jobId, "RESUMED", "Resumed", actor);
	}

	/** Waiting work is cancelled; in-flight items finish, their results are kept and their children dropped. */
	@Transactional
	public void cancel(long jobId, String actor) {
		requireJob(jobId);
		if (!this.jobs.transition(jobId, JobStatus.CANCELLED, JobStatus.CREATED, JobStatus.DISCOVERING,
				JobStatus.RUNNING, JobStatus.PAUSED)) {
			throw JobException.conflict("Job %d is already finished".formatted(jobId));
		}
		int cancelled = this.items.cancelPending(jobId);
		this.events.info(jobId, "CANCELLED", "Cancelled; %d waiting item(s) dropped".formatted(cancelled), actor);
	}

	/** Re-queues every dead item with a fresh attempt budget, reopening the job if it had finished with errors. */
	@Transactional
	public int retryDead(long jobId, String actor) {
		Job job = requireJob(jobId);
		if (job.status() == JobStatus.COMPLETED_WITH_ERRORS) {
			try {
				this.jobs.transition(jobId, JobStatus.RUNNING, JobStatus.COMPLETED_WITH_ERRORS);
			}
			catch (DuplicateKeyException ex) {
				throw JobException.conflict("Another job for this OEM is active; retry once it has finished");
			}
		}
		else if (!JobStatus.ACTIVE.contains(job.status())) {
			throw JobException.conflict("Job %d is %s; dead items cannot be retried".formatted(jobId, job.status()));
		}
		int retried = this.items.retryDead(jobId);
		this.events.info(jobId, "RETRY_DEAD", "Re-queued %d dead item(s)".formatted(retried), actor);
		return retried;
	}

	public Optional<Job> find(long jobId) {
		return this.jobs.findById(jobId);
	}

	private Job requireJob(long jobId) {
		return this.jobs.findById(jobId).orElseThrow(() -> JobException.notFound("No job " + jobId));
	}

}
