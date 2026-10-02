package com.example.oemintel.job;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.oemintel.crawl.UrlNormalizer;
import com.example.oemintel.engine.NewWorkItem;
import com.example.oemintel.engine.TaskType;
import com.example.oemintel.engine.WorkItemRepository;

/**
 * Admits discovered candidates within the job's limit (SDD 9.1):
 * <ul>
 * <li>FAMILY: exactly N valid candidates; a candidate rejected at fetch time frees its slot for the next one;</li>
 * <li>SKU: waves of families until the extracted SKU count reaches the limit; a family is never cut, so the result
 * can overshoot, and the overshoot is shown.</li>
 * </ul>
 * Order is deterministic (order key, then id), so a re-run selects the same set.
 */
@Service
public class CandidateAdmissionService {

	static final int SKU_WAVE_SIZE = 10;

	/** Namespace for the per-job advisory lock that serialises admission decisions. */
	private static final int ADMISSION_LOCK_NAMESPACE = 7001;

	private final JdbcClient jdbc;

	private final JobRepository jobs;

	private final CandidateRepository candidates;

	private final WorkItemRepository items;

	private final JobEventRepository events;

	private final JsonMapper json;

	public CandidateAdmissionService(JdbcClient jdbc, JobRepository jobs, CandidateRepository candidates,
			WorkItemRepository items, JobEventRepository events, JsonMapper json) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.candidates = candidates;
		this.items = items;
		this.events = events;
		this.json = json;
	}

	/** Called once discovery is finished: the job moves to RUNNING and the first candidates are admitted. */
	@Transactional
	public void startAdmission(long jobId) {
		if (!this.jobs.transition(jobId, JobStatus.RUNNING, JobStatus.DISCOVERING)) {
			return;
		}
		int discovered = this.candidates.countByStatus(jobId, "DISCOVERED");
		int unknown = this.candidates.countByStatus(jobId, "UNKNOWN");
		this.events.info(jobId, "DISCOVERY_COMPLETE",
				"Discovery complete: %d candidate(s), %d with an unknown URL pattern".formatted(discovered, unknown),
				null);
		topUp(jobId);
	}

	/**
	 * Admits more candidates if the limit allows. Safe to call from anywhere at any time: decisions for one job are
	 * serialised by an advisory lock held until the surrounding transaction ends.
	 * @return how many candidates were admitted
	 */
	@Transactional
	public int topUp(long jobId) {
		lockJob(jobId);
		Job job = this.jobs.findById(jobId).orElseThrow();
		if (job.status() != JobStatus.RUNNING || !job.mode().admitsCandidates()) {
			return 0;
		}
		boolean includeExisting = job.existingPolicy() == Job.ExistingPolicy.REFRESH;
		int count = switch (job.limitUnit()) {
			case FAMILY -> job.limitValue() - this.candidates.countByStatus(jobId, "ADMITTED", "COMPLETED");
			case SKU -> skuWave(job);
		};
		if (count <= 0) {
			return 0;
		}
		List<CandidateRepository.AdmittedCandidate> admitted = this.candidates.admitNext(jobId, count,
				includeExisting);
		for (CandidateRepository.AdmittedCandidate candidate : admitted) {
			NewWorkItem fetch = NewWorkItem.of(TaskType.FETCH_PAGE, candidate.url(), candidate.normalizedUrl())
				.withHost(UrlNormalizer.host(candidate.normalizedUrl()))
				.withPayload(this.json.writeValueAsString(Map.of("candidateId", candidate.id())));
			this.items.enqueue(jobId, null, fetch);
		}
		if (!admitted.isEmpty()) {
			this.events.info(jobId, "ADMITTED", "Admitted %d candidate(s)".formatted(admitted.size()), null);
		}
		return admitted.size();
	}

	/**
	 * Serialises admission decisions for one job until the surrounding transaction ends. Re-entrant: PostgreSQL
	 * advisory locks stack within a session. The state evaluator takes it before reading the job's work, so a
	 * replacement admitted by a concurrent rejection can never be missed when deciding the job is complete.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockJob(long jobId) {
		this.jdbc.sql("select 1 from (select pg_advisory_xact_lock(:ns, :key)) held")
			.param("ns", ADMISSION_LOCK_NAMESPACE)
			.param("key", (int) (jobId % Integer.MAX_VALUE))
			.query(Integer.class)
			.single();
	}

	/** SKU counts are only known after extraction, so the next wave waits until the current one is finished. */
	private int skuWave(Job job) {
		if (this.candidates.countByStatus(job.id(), "ADMITTED") > 0) {
			return 0;
		}
		return (this.candidates.completedSkuCount(job.id()) >= job.limitValue()) ? 0 : SKU_WAVE_SIZE;
	}

	/**
	 * Releases the slot of a candidate that turned out not to be a valid product (soft 404, not a product page) or a
	 * duplicate of an admitted one, and admits the next candidate in its place.
	 */
	@Transactional
	public void reject(long candidateId, boolean duplicate, String reason) {
		this.candidates.close(candidateId, duplicate ? "DUPLICATE" : "REJECTED", reason, null, null)
			.ifPresent(this::topUp);
	}

	/** Records a candidate that became a family; in SKU mode its SKU count feeds the next wave decision. */
	@Transactional
	public void complete(long candidateId, Long familyId, int skuCount) {
		this.candidates.close(candidateId, "COMPLETED", null, familyId, skuCount);
	}

}
