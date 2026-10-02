package com.example.oemintel.engine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.oemintel.TestcontainersConfiguration;
import com.example.oemintel.job.CreateJobRequest;
import com.example.oemintel.job.JobMode;
import com.example.oemintel.job.JobService;

import static org.assertj.core.api.Assertions.assertThat;

/** The queue's guarantees, exercised directly with the engine switched off. */
@SpringBootTest(properties = "oemintel.engine.enabled=false")
@Import(TestcontainersConfiguration.class)
class WorkItemRepositoryTests {

	private static final String OWNER = "test-owner";

	@Autowired
	WorkItemRepository items;

	@Autowired
	JobService jobService;

	@Autowired
	JdbcClient jdbc;

	long jobId;

	@BeforeEach
	void createJob() {
		// An offline job starts RUNNING, so its items are leasable without any network authorisation.
		this.jobId = this.jobService
			.create(new CreateJobRequest("ROXTEC", JobMode.RENORMALIZE, null, 1, null, null, null, null), "test")
			.id();
		this.jdbc.sql("delete from work_item where job_id = :jobId").param("jobId", this.jobId).update();
	}

	@AfterEach
	void finishJobs() {
		this.jdbc.sql("update onboarding_job set status = 'CANCELLED' where status in ('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED')")
			.update();
	}

	@Test
	void enqueueIsIdempotentPerDedupeKey() {
		NewWorkItem item = NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-1", "family-1");
		assertThat(this.items.enqueue(this.jobId, null, item)).isTrue();
		assertThat(this.items.enqueue(this.jobId, null, item)).isFalse();
		assertThat(countItems()).isEqualTo(1);
	}

	@Test
	void enqueueIsRefusedForAFinishedJob() {
		this.jobService.cancel(this.jobId, "test");
		assertThat(this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.EXTRACT_FAMILY, "x", "x"))).isFalse();
	}

	@Test
	void completionWithAStaleLeaseTokenIsFenced() {
		this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-1", "family-1"));
		WorkItem first = leaseOne();
		expireLeases();
		assertThat(this.items.reapExpiredLeases()).isEqualTo(1);
		WorkItem second = leaseOne();

		assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
		assertThat(this.items.markDone(first.id(), first.leaseToken())).isFalse();
		assertThat(this.items.markDone(second.id(), second.leaseToken())).isTrue();
		assertThat(status(second.id())).isEqualTo("DONE");
	}

	@Test
	void rateLimitedRetryDoesNotConsumeAnAttempt() {
		this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-1", "family-1"));
		WorkItem leased = leaseOne();
		assertThat(leased.attempts()).isEqualTo(1);
		this.items.reschedule(leased.id(), leased.leaseToken(), ItemStatus.RETRY_WAIT, Duration.ZERO, true,
				ErrorClass.RATE_LIMITED, "429");
		assertThat(leaseOne().attempts()).isEqualTo(1);
	}

	@Test
	void reaperKillsAnItemThatExhaustedItsAttempts() {
		this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-1", "family-1"));
		for (int attempt = 1; attempt <= TaskType.EXTRACT_FAMILY.maxAttempts(); attempt++) {
			WorkItem leased = leaseOne();
			assertThat(leased.attempts()).isEqualTo(attempt);
			expireLeases();
			this.items.reapExpiredLeases();
		}
		assertThat(this.jdbc.sql("select status || '/' || error_class from work_item where job_id = :jobId")
			.param("jobId", this.jobId)
			.query(String.class)
			.single()).isEqualTo("DEAD/LEASE_EXPIRED");
	}

	@Test
	void futureRetriesAreNotLeasedEarly() {
		this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-1", "family-1"));
		WorkItem leased = leaseOne();
		this.items.reschedule(leased.id(), leased.leaseToken(), ItemStatus.RETRY_WAIT, Duration.ofMinutes(5), false,
				ErrorClass.TRANSIENT, "timeout");
		assertThat(this.items.lease(Lane.LOCAL, Set.of(TaskType.EXTRACT_FAMILY), 10, OWNER)).isEmpty();
	}

	@Test
	void networkLeasingAllowsOneRequestPerHostAndSkipsHostsThatAreNotReady() {
		enqueueFetch("https://a.test/1", "a.test");
		enqueueFetch("https://a.test/2", "a.test");
		enqueueFetch("https://b.test/1", "b.test");
		Set<TaskType> types = Set.of(TaskType.FETCH_PAGE);

		Optional<WorkItem> first = this.items.leaseNetwork(types, Set.of("b.test"), OWNER);
		assertThat(first).map(WorkItem::host).contains("a.test");
		assertThat(this.items.leaseNetwork(types, Set.of("b.test"), OWNER)).isEmpty();

		Optional<WorkItem> second = this.items.leaseNetwork(types, Set.of(), OWNER);
		assertThat(second).map(WorkItem::host).contains("b.test");
		assertThat(this.items.leaseNetwork(types, Set.of(), OWNER)).isEmpty();
	}

	@Test
	void pausedJobsAreNotLeased() {
		this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-1", "family-1"));
		this.jobService.pause(this.jobId, "maintenance", "test");
		assertThat(this.items.lease(Lane.LOCAL, Set.of(TaskType.EXTRACT_FAMILY), 10, OWNER)).isEmpty();
		this.jobService.resume(this.jobId, "test");
		assertThat(this.items.lease(Lane.LOCAL, Set.of(TaskType.EXTRACT_FAMILY), 10, OWNER)).hasSize(1);
	}

	private void enqueueFetch(String url, String host) {
		this.items.enqueue(this.jobId, null, NewWorkItem.of(TaskType.FETCH_PAGE, url, url).withHost(host));
	}

	private WorkItem leaseOne() {
		List<WorkItem> leased = this.items.lease(Lane.LOCAL, Set.of(TaskType.EXTRACT_FAMILY), 1, OWNER);
		assertThat(leased).hasSize(1);
		return leased.get(0);
	}

	private void expireLeases() {
		this.jdbc.sql("update work_item set lease_expires_at = now() - interval '1 second' where job_id = :jobId and status = 'LEASED'")
			.param("jobId", this.jobId)
			.update();
	}

	private int countItems() {
		return this.jdbc.sql("select count(*) from work_item where job_id = :jobId")
			.param("jobId", this.jobId)
			.query(Integer.class)
			.single();
	}

	private String status(long itemId) {
		return this.jdbc.sql("select status from work_item where id = :id").param("id", itemId).query(String.class).single();
	}

}
