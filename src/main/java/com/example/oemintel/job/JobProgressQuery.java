package com.example.oemintel.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read model for job progress: counts are aggregated from work items on demand, never stored. */
@Component
public class JobProgressQuery {

	private final JdbcClient jdbc;

	private final JobEventRepository events;

	public JobProgressQuery(JdbcClient jdbc, JobEventRepository events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	public JobProgress progress(long jobId) {
		Map<String, Map<String, Long>> items = new LinkedHashMap<>();
		this.jdbc.sql("""
				select task_type, status, count(*) as n from work_item
				 where job_id = :jobId group by task_type, status order by task_type, status
				""").param("jobId", jobId).query((rs) -> {
			items.computeIfAbsent(rs.getString("task_type"), (type) -> new LinkedHashMap<>())
				.put(rs.getString("status"), rs.getLong("n"));
		});
		Map<String, Long> candidates = new LinkedHashMap<>();
		this.jdbc.sql("""
				select status, count(*) as n from job_candidate where job_id = :jobId group by status order by status
				""").param("jobId", jobId).query((rs) -> {
			candidates.put(rs.getString("status"), rs.getLong("n"));
		});
		return new JobProgress(items, candidates, this.events.recent(jobId, 20));
	}

	public List<JobRow> recentJobs(int limit) {
		return this.jdbc.sql("""
				select j.id, o.code as oem, j.mode, j.status, j.limit_value, j.limit_unit, j.created_at, j.finished_at,
				       count(w.id) filter (where w.status in ('DONE', 'DEAD', 'SKIPPED', 'CANCELLED')) as finished_items,
				       count(w.id) as total_items,
				       count(w.id) filter (where w.status = 'DEAD') as dead_items,
				       (select count(*) from job_candidate c where c.job_id = j.id and c.status = 'COMPLETED') as families
				  from onboarding_job j
				  join oem o on o.id = j.oem_id
				  left join work_item w on w.job_id = j.id
				 where not j.archived
				 group by j.id, o.code
				 order by j.id desc
				 limit :limit
				""")
			.param("limit", limit)
			.query((rs, row) -> new JobRow(rs.getLong("id"), rs.getString("oem"), rs.getString("mode"),
					rs.getString("status"), rs.getInt("limit_value"), rs.getString("limit_unit"),
					rs.getObject("created_at", java.time.OffsetDateTime.class),
					rs.getObject("finished_at", java.time.OffsetDateTime.class), rs.getLong("finished_items"),
					rs.getLong("total_items"), rs.getLong("dead_items"), rs.getLong("families")))
			.list();
	}

	public List<CandidateRow> candidates(long jobId) {
		return this.jdbc.sql("""
				select c.url, c.status, c.reason, c.sku_count, c.family_id, f.display_name
				  from job_candidate c left join product_family f on f.id = c.family_id
				 where c.job_id = :jobId order by c.order_key, c.id
				""")
			.param("jobId", jobId)
			.query((rs, row) -> {
				long familyId = rs.getLong("family_id");
				Long family = rs.wasNull() ? null : familyId;
				int skus = rs.getInt("sku_count");
				return new CandidateRow(rs.getString("url"), rs.getString("status"), rs.getString("reason"),
						rs.wasNull() ? null : skus, family, rs.getString("display_name"));
			})
			.list();
	}

	public List<ErrorRow> errors(long jobId) {
		return this.jdbc.sql("""
				select task_type, status, error_class, last_error, target from work_item
				 where job_id = :jobId and last_error is not null and status in ('DEAD', 'RETRY_WAIT', 'SKIPPED')
				 order by updated_at desc limit 50
				""")
			.param("jobId", jobId)
			.query((rs, row) -> new ErrorRow(rs.getString("task_type"), rs.getString("status"),
					rs.getString("error_class"), rs.getString("last_error"), rs.getString("target")))
			.list();
	}

	public record JobRow(long id, String oem, String mode, String status, int limitValue, String limitUnit,
			java.time.OffsetDateTime createdAt, java.time.OffsetDateTime finishedAt, long finishedItems,
			long totalItems, long deadItems, long families) {

		public boolean active() {
			return JobStatus.ACTIVE.contains(JobStatus.valueOf(this.status));
		}

	}

	public record CandidateRow(String url, String status, String reason, Integer skuCount, Long familyId,
			String familyName) {
	}

	public record ErrorRow(String taskType, String status, String errorClass, String error, String target) {
	}

	/**
	 * @param itemsByTypeAndStatus task type → item status → count
	 * @param candidatesByStatus candidate status → count
	 */
	public record JobProgress(Map<String, Map<String, Long>> itemsByTypeAndStatus, Map<String, Long> candidatesByStatus,
			List<JobEventRepository.JobEvent> recentEvents) {
	}

}
