package com.example.oemintel.engine;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.oemintel.TestcontainersConfiguration;
import com.example.oemintel.job.CandidateAdmissionService;
import com.example.oemintel.job.CandidateRepository;
import com.example.oemintel.job.CreateJobRequest;
import com.example.oemintel.job.Job;
import com.example.oemintel.job.JobMode;
import com.example.oemintel.job.JobService;
import com.example.oemintel.job.JobStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Step 3 acceptance with the engine running for real against PostgreSQL, using fake handlers. */
@SpringBootTest(properties = { "oemintel.engine.poll-interval=20ms", "oemintel.engine.evaluator-interval=100ms",
		"oemintel.engine.reaper-interval=500ms", "oemintel.engine.retry.base-delay=20ms",
		"oemintel.engine.retry.jitter=0", "oemintel.engine.lanes.local-concurrency=8",
		"oemintel.pipeline.enabled=false" })
@Import({ TestcontainersConfiguration.class, EngineIntegrationTests.FakeHandlers.class })
class EngineIntegrationTests {

	static final int FAN_OUT = 2000;

	/** Candidates (by position) that turn out not to be product pages once fetched. */
	static final Set<Integer> NOT_PRODUCTS = Set.of(3, 7, 11, 15, 19);

	static final AtomicInteger successfulHandles = new AtomicInteger();

	@Autowired
	JobService jobService;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc.sql("create table if not exists test_effect (work_item_id bigint primary key)").update();
		successfulHandles.set(0);
	}

	@AfterEach
	void tearDown() {
		this.jdbc.sql("update onboarding_job set status = 'CANCELLED' where status in ('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED')")
			.update();
	}

	@Test
	void chaosRunLosesNothingAndCommitsNothingTwice() {
		Job job = this.jobService.create(new CreateJobRequest("ROXTEC", JobMode.RENORMALIZE, null, 1, null, null, null,
				null), "test");

		JobStatus finalStatus = awaitFinished(job.id(), Duration.ofSeconds(120));

		List<Long> extractIds = this.jdbc.sql("""
				select id from work_item where job_id = :jobId and task_type = 'EXTRACT_FAMILY'
				""").param("jobId", job.id()).query(Long.class).list();
		long expectedDead = extractIds.stream().filter(FakeHandlers::isPermanentFailure).count();
		assertThat(extractIds).hasSize(FAN_OUT);
		assertThat(finalStatus).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
		assertThat(countByStatus(job.id(), "DEAD")).isEqualTo(expectedDead);
		assertThat(countByStatus(job.id(), "DONE")).isEqualTo(FAN_OUT - expectedDead + 1);
		long effects = this.jdbc.sql("""
				select count(*) from test_effect e join work_item w on w.id = e.work_item_id where w.job_id = :jobId
				""").param("jobId", job.id()).query(Long.class).single();
		assertThat(effects).isEqualTo(FAN_OUT - expectedDead);
		assertThat(successfulHandles.get()).isEqualTo(FAN_OUT - expectedDead);
		assertThat(this.jdbc.sql("""
				select count(*) from work_item
				 where job_id = :jobId and task_type = 'EXTRACT_FAMILY' and status = 'DONE' and attempts = 2
				""").param("jobId", job.id()).query(Long.class).single())
			.as("items that failed once transiently and then succeeded")
			.isPositive();
	}

	@Test
	void admissionFillsTheFamilyLimitExactlyAndReplacesRejectedCandidates() {
		Job job = this.jobService.create(new CreateJobRequest("ROXTEC", JobMode.FULL, null, 20, null, null, null, null),
				"test");

		JobStatus finalStatus = awaitFinished(job.id(), Duration.ofSeconds(60));

		assertThat(finalStatus).isEqualTo(JobStatus.COMPLETED);
		List<String> completed = candidateUrls(job.id(), "COMPLETED");
		List<String> rejected = candidateUrls(job.id(), "REJECTED");
		assertThat(completed).hasSize(20);
		assertThat(rejected).hasSize(NOT_PRODUCTS.size());
		// Deterministic order: exactly the first 25 candidates were admitted.
		List<String> firstTwentyFive = IntStream.range(0, 25).mapToObj(FakeHandlers::candidateUrl).toList();
		assertThat(completed).doesNotContainAnyElementsOf(rejected);
		assertThat(completed).allMatch(firstTwentyFive::contains);
		assertThat(candidateUrls(job.id(), "DISCOVERED")).hasSize(25);
	}

	private JobStatus awaitFinished(long jobId, Duration timeout) {
		await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> !JobStatus.ACTIVE.contains(status(jobId)));
		return status(jobId);
	}

	private JobStatus status(long jobId) {
		return this.jobService.find(jobId).orElseThrow().status();
	}

	private long countByStatus(long jobId, String status) {
		return this.jdbc.sql("select count(*) from work_item where job_id = :jobId and status = :status")
			.param("jobId", jobId)
			.param("status", status)
			.query(Long.class)
			.single();
	}

	private List<String> candidateUrls(long jobId, String status) {
		return this.jdbc.sql("select url from job_candidate where job_id = :jobId and status = :status order by order_key")
			.param("jobId", jobId)
			.param("status", status)
			.query(String.class)
			.list();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeHandlers {

		static boolean isPermanentFailure(long itemId) {
			return itemId % 100 == 0;
		}

		static String candidateUrl(int position) {
			return "https://shop.test/p/%03d".formatted(position);
		}

		/** Fans out into the chaos workload. */
		@Bean
		TaskHandler planHandler() {
			return handler(TaskType.PLAN_REPROCESS, (item, context) -> TaskResult.withChildren(IntStream.range(0, FAN_OUT)
				.mapToObj((i) -> NewWorkItem.of(TaskType.EXTRACT_FAMILY, "family-" + i, "family-" + i))
				.toList()));
		}

		/** 1% fail permanently, 20% fail transiently on their first attempt, the rest commit an effect. */
		@Bean
		TaskHandler chaosHandler(JdbcClient jdbc) {
			return handler(TaskType.EXTRACT_FAMILY, (item, context) -> {
				Thread.sleep(ThreadLocalRandom.current().nextInt(3));
				if (isPermanentFailure(item.id())) {
					throw TaskFailure.permanent("not a product");
				}
				if (item.attempts() == 1 && item.id() % 5 == 1) {
					throw TaskFailure.transientFailure("simulated timeout", null);
				}
				successfulHandles.incrementAndGet();
				return TaskResult.committing(() -> jdbc.sql("insert into test_effect (work_item_id) values (:id)")
					.param("id", item.id())
					.update());
			});
		}

		/** Discovers 50 product URLs. */
		@Bean
		TaskHandler discoverHandler(CandidateRepository candidates) {
			return handler(TaskType.DISCOVER, (item, context) -> TaskResult.committing(() -> {
				for (int i = 0; i < 50; i++) {
					candidates.upsert(item.jobId(), candidateUrl(i), candidateUrl(i), "%03d".formatted(i), "TEST");
				}
			}));
		}

		/** Some admitted candidates turn out not to be products; their slots must be refilled. */
		@Bean
		TaskHandler fetchHandler(CandidateAdmissionService admission, JsonMapper json) {
			return handler(TaskType.FETCH_PAGE, (item, context) -> {
				long candidateId = json.readTree(item.payload()).get("candidateId").asLong();
				int position = Integer.parseInt(item.target().substring(item.target().lastIndexOf('/') + 1));
				return TaskResult.committing(() -> {
					if (NOT_PRODUCTS.contains(position)) {
						admission.reject(candidateId, false, "not a product page");
					}
					else {
						admission.complete(candidateId, null, 1);
					}
				});
			});
		}

		private static TaskHandler handler(TaskType type, Body body) {
			return new TaskHandler() {

				@Override
				public TaskType taskType() {
					return type;
				}

				@Override
				public TaskResult handle(WorkItem item, TaskContext context) throws Exception {
					return body.handle(item, context);
				}

			};
		}

		@FunctionalInterface
		interface Body {

			TaskResult handle(WorkItem item, TaskContext context) throws Exception;

		}

	}

}
