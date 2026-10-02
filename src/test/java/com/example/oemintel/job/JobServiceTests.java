package com.example.oemintel.job;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.oemintel.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "oemintel.engine.enabled=false")
@Import(TestcontainersConfiguration.class)
class JobServiceTests {

	@Autowired
	JobService jobService;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void reset() {
		this.jdbc.sql("update onboarding_job set status = 'CANCELLED' where status in ('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED')")
			.update();
		setAuthorisation("PENDING");
	}

	@Test
	void fullCrawlIsRefusedWhileAuthorisationIsPending() {
		assertThatThrownBy(() -> create(JobMode.FULL, 500))
			.isInstanceOfSatisfying(JobException.class, (ex) -> assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN))
			.hasMessageContaining("PENDING");
	}

	@Test
	void smallRunsAndDryRunsAreAllowedWhileAuthorisationIsPending() {
		Job small = create(JobMode.FULL, JobService.UNAUTHORISED_FAMILY_LIMIT);
		assertThat(small.status()).isEqualTo(JobStatus.DISCOVERING);
		this.jobService.cancel(small.id(), "test");
		assertThat(create(JobMode.DRY_RUN, 500).status()).isEqualTo(JobStatus.DISCOVERING);
	}

	@Test
	void fullCrawlIsAllowedOnceAuthorised() {
		setAuthorisation("AUTHORISED");
		assertThat(create(JobMode.FULL, 500).status()).isEqualTo(JobStatus.DISCOVERING);
	}

	@Test
	void everyNetworkJobIsRefusedWhenAuthorisationIsDenied() {
		setAuthorisation("DENIED");
		assertThatThrownBy(() -> create(JobMode.DRY_RUN, 10)).isInstanceOf(JobException.class);
		assertThat(create(JobMode.RENORMALIZE, 10).status()).isEqualTo(JobStatus.RUNNING);
	}

	@Test
	void onlyOneActiveJobPerOem() {
		Job first = create(JobMode.RENORMALIZE, 1);
		assertThatThrownBy(() -> create(JobMode.REEXTRACT, 1))
			.isInstanceOfSatisfying(JobException.class, (ex) -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT))
			.hasMessageContaining(Long.toString(first.id()));
	}

	@Test
	void seedListCreatesCandidatesInGivenOrderAndRejectsBadUrls() {
		assertThatThrownBy(() -> this.jobService.create(new CreateJobRequest("ROXTEC", JobMode.SEED_LIST, null, 5, null,
				null, null, List.of("https://www.roxtec.com/a", "not a url")), "test"))
			.hasMessageContaining("not a url");

		Job job = this.jobService.create(new CreateJobRequest("ROXTEC", JobMode.SEED_LIST, null, 5, null, null, null,
				List.of("https://WWW.Roxtec.com:443/b#top", "https://www.roxtec.com/a")), "test");
		assertThat(this.jdbc.sql("select normalized_url from job_candidate where job_id = :jobId order by order_key")
			.param("jobId", job.id())
			.query(String.class)
			.list()).containsExactly("https://www.roxtec.com/b", "https://www.roxtec.com/a");
	}

	@Test
	void pauseResumeAndCancelFollowTheLifecycle() {
		Job job = create(JobMode.RENORMALIZE, 1);
		this.jobService.pause(job.id(), "maintenance", "test");
		assertThat(this.jobService.find(job.id())).map(Job::status).contains(JobStatus.PAUSED);
		assertThatThrownBy(() -> this.jobService.pause(job.id(), null, "test")).isInstanceOf(JobException.class);
		this.jobService.resume(job.id(), "test");
		assertThat(this.jobService.find(job.id())).map(Job::status).contains(JobStatus.RUNNING);
		this.jobService.cancel(job.id(), "test");
		assertThat(this.jobService.find(job.id())).map(Job::status).contains(JobStatus.CANCELLED);
		assertThat(this.jdbc.sql("select status from work_item where job_id = :jobId")
			.param("jobId", job.id())
			.query(String.class)
			.list()).containsOnly("CANCELLED");
		assertThatThrownBy(() -> this.jobService.cancel(job.id(), "test")).isInstanceOf(JobException.class);
	}

	private Job create(JobMode mode, int limit) {
		return this.jobService.create(new CreateJobRequest("ROXTEC", mode, null, limit, null, null, null, null), "test");
	}

	private void setAuthorisation(String status) {
		this.jdbc.sql("update oem set crawl_auth_status = :status where code = 'ROXTEC'").param("status", status).update();
	}

}
