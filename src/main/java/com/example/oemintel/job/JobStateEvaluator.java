package com.example.oemintel.job;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.oemintel.engine.EngineProperties;
import com.example.oemintel.engine.EngineTicker;
import com.example.oemintel.engine.TaskType;

/**
 * Derives job status from its work (progress is never stored as counters): discovery finished → admission;
 * nothing left to run or admit → completed; work due but nothing moving for too long → a STALLED event.
 */
@Component
public class JobStateEvaluator implements EngineTicker {

	private static final Logger log = LoggerFactory.getLogger(JobStateEvaluator.class);

	private static final String DISCOVERY_TYPES = Arrays.stream(TaskType.values())
		.filter(TaskType::isDiscovery)
		.map(Enum::name)
		.collect(Collectors.joining(","));

	private final JdbcClient jdbc;

	private final JobRepository jobs;

	private final JobEventRepository events;

	private final CandidateAdmissionService admission;

	private final TransactionTemplate tx;

	private final Duration interval;

	private final Duration stallAfter;

	public JobStateEvaluator(JdbcClient jdbc, JobRepository jobs, JobEventRepository events,
			CandidateAdmissionService admission, TransactionTemplate tx, EngineProperties properties) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.events = events;
		this.admission = admission;
		this.tx = tx;
		this.interval = properties.evaluatorInterval();
		this.stallAfter = properties.stallAfter();
	}

	@Override
	public Duration interval() {
		return this.interval;
	}

	@Override
	public void tick() {
		for (long jobId : this.jobs.findIdsByStatus(JobStatus.DISCOVERING, JobStatus.RUNNING)) {
			try {
				this.tx.executeWithoutResult((status) -> evaluate(jobId));
			}
			catch (RuntimeException ex) {
				log.warn("Evaluating job {} failed: {}", jobId, ex.getMessage(), ex);
			}
		}
	}

	/** Evaluates one job now. Public so tests and the dashboard's "refresh" can drive it synchronously. */
	public void evaluate(long jobId) {
		Job job = this.jobs.findByIdForUpdate(jobId).orElseThrow();
		this.admission.lockJob(jobId);
		Snapshot work = snapshot(jobId);
		if (job.status() == JobStatus.DISCOVERING && work.activeDiscovery() == 0 && job.mode().admitsCandidates()) {
			this.admission.startAdmission(jobId);
			return;
		}
		boolean waitingForDiscovery = job.status() == JobStatus.DISCOVERING && work.activeDiscovery() > 0;
		int admitted = (job.status() == JobStatus.RUNNING) ? this.admission.topUp(jobId) : 0;
		if (!waitingForDiscovery && work.active() == 0 && admitted == 0) {
			JobStatus outcome = (work.dead() > 0) ? JobStatus.COMPLETED_WITH_ERRORS : JobStatus.COMPLETED;
			if (this.jobs.transition(jobId, outcome, JobStatus.DISCOVERING, JobStatus.RUNNING)) {
				this.events.info(jobId, outcome.name(), (work.dead() > 0)
						? "Finished with %d dead item(s); they can be retried from the errors view".formatted(work.dead())
						: "Finished", null);
			}
			return;
		}
		if (work.stalled()) {
			this.events.warn(jobId, "STALLED", ("Work is due but nothing has completed for %d minute(s). Check that a "
					+ "handler exists for the pending task types and that the host is not paused.")
				.formatted(this.stallAfter.toMinutes()), null);
		}
	}

	private Snapshot snapshot(long jobId) {
		return this.jdbc.sql("""
				select count(*) filter (where status in ('PENDING', 'LEASED', 'RETRY_WAIT')) as active,
				       count(*) filter (where status in ('PENDING', 'LEASED', 'RETRY_WAIT')
				                          and task_type = any(string_to_array(:discoveryTypes, ','))) as active_discovery,
				       count(*) filter (where status = 'DEAD') as dead,
				       (count(*) filter (where status in ('PENDING', 'RETRY_WAIT') and next_attempt_at <= now()) > 0
				        and count(*) filter (where status = 'LEASED') = 0
				        and coalesce(max(finished_at), (select started_at from onboarding_job where id = :jobId))
				            < now() - make_interval(secs => :stallSeconds)
				        and not exists (select 1 from job_event e
				                         where e.job_id = :jobId and e.event_type = 'STALLED'
				                           and e.created_at > now() - make_interval(secs => :stallSeconds))) as stalled
				  from work_item where job_id = :jobId
				""")
			.param("discoveryTypes", DISCOVERY_TYPES)
			.param("jobId", jobId)
			.param("stallSeconds", (double) this.stallAfter.toSeconds())
			.query((rs, row) -> new Snapshot(rs.getLong("active"), rs.getLong("active_discovery"), rs.getLong("dead"),
					rs.getBoolean("stalled")))
			.single();
	}

	private record Snapshot(long active, long activeDiscovery, long dead, boolean stalled) {
	}

}
