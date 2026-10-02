package com.example.oemintel.job;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The human-readable job timeline shown in the dashboard. */
@Repository
public class JobEventRepository {

	private final JdbcClient jdbc;

	public JobEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void info(long jobId, String type, String message, String actor) {
		record(jobId, "INFO", type, message, actor);
	}

	public void warn(long jobId, String type, String message, String actor) {
		record(jobId, "WARN", type, message, actor);
	}

	public void record(long jobId, String level, String type, String message, String actor) {
		this.jdbc.sql("""
				insert into job_event (job_id, level, event_type, message, actor)
				values (:jobId, :level, :type, :message, :actor)
				""")
			.param("jobId", jobId)
			.param("level", level)
			.param("type", type)
			.param("message", message)
			.param("actor", actor)
			.update();
	}

	public List<JobEvent> recent(long jobId, int limit) {
		return this.jdbc.sql("""
				select level, event_type, message, actor, created_at from job_event
				 where job_id = :jobId order by created_at desc, id desc limit :limit
				""")
			.param("jobId", jobId)
			.param("limit", limit)
			.query((rs, row) -> new JobEvent(rs.getString("level"), rs.getString("event_type"),
					rs.getString("message"), rs.getString("actor"), rs.getObject("created_at", OffsetDateTime.class)))
			.list();
	}

	public record JobEvent(String level, String type, String message, String actor, OffsetDateTime at) {
	}

}
