package com.example.oemintel.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

	private final JdbcClient jdbc;

	public JobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @throws org.springframework.dao.DuplicateKeyException if the OEM already has an active job */
	public long insert(long oemId, JobMode mode, String locale, int limitValue, Job.LimitUnit limitUnit,
			Job.ExistingPolicy existingPolicy, String optionsJson, JobStatus status, String createdBy) {
		return this.jdbc.sql("""
				insert into onboarding_job (oem_id, mode, locale, limit_value, limit_unit, existing_policy, options,
				                            status, created_by, started_at)
				values (:oemId, :mode, :locale, :limitValue, :limitUnit, :existingPolicy, cast(:options as jsonb),
				        :status, :createdBy, now())
				returning id
				""")
			.param("oemId", oemId)
			.param("mode", mode.name())
			.param("locale", locale)
			.param("limitValue", limitValue)
			.param("limitUnit", limitUnit.name())
			.param("existingPolicy", existingPolicy.name())
			.param("options", optionsJson)
			.param("status", status.name())
			.param("createdBy", createdBy)
			.query(Long.class)
			.single();
	}

	public Optional<Job> findById(long id) {
		return this.jdbc.sql("select * from onboarding_job where id = :id")
			.param("id", id)
			.query(JobRepository::map)
			.optional();
	}

	/**
	 * Locks the job row against concurrent status changes for the rest of the transaction. NO KEY UPDATE, not
	 * UPDATE: work items reference the job by foreign key, and inserting them must not wait on this lock.
	 */
	public Optional<Job> findByIdForUpdate(long id) {
		return this.jdbc.sql("select * from onboarding_job where id = :id for no key update")
			.param("id", id)
			.query(JobRepository::map)
			.optional();
	}

	public Optional<Long> findActiveJobId(long oemId) {
		return this.jdbc.sql("""
				select id from onboarding_job
				 where oem_id = :oemId and status in ('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED')
				""").param("oemId", oemId).query(Long.class).optional();
	}

	public List<Long> findIdsByStatus(JobStatus... statuses) {
		return this.jdbc.sql("select id from onboarding_job where status = any(string_to_array(:statuses, ',')) order by id")
			.param("statuses", String.join(",", Arrays.stream(statuses).map(Enum::name).toList()))
			.query(Long.class)
			.list();
	}

	/**
	 * Moves the job from one of the expected statuses to the new one. Returns {@code false} if the job was not
	 * in an expected status, which makes concurrent transitions (two admins cancelling) harmless.
	 */
	public boolean transition(long id, JobStatus to, JobStatus... from) {
		boolean terminal = !JobStatus.ACTIVE.contains(to);
		return this.jdbc.sql("""
				update onboarding_job
				   set status = :to,
				       finished_at = case when :terminal then now() else null end,
				       updated_at = now()
				 where id = :id and status = any(string_to_array(:from, ','))
				""")
			.param("to", to.name())
			.param("terminal", terminal)
			.param("id", id)
			.param("from", String.join(",", Arrays.stream(from).map(Enum::name).toList()))
			.update() == 1;
	}

	public boolean pause(long id, String reason) {
		return this.jdbc.sql("""
				update onboarding_job
				   set status_before_pause = status, status = 'PAUSED', pause_reason = :reason, updated_at = now()
				 where id = :id and status in ('DISCOVERING', 'RUNNING')
				""").param("id", id).param("reason", reason).update() == 1;
	}

	public boolean resume(long id) {
		return this.jdbc.sql("""
				update onboarding_job
				   set status = status_before_pause, status_before_pause = null, pause_reason = null, updated_at = now()
				 where id = :id and status = 'PAUSED'
				""").param("id", id).update() == 1;
	}

	private static Job map(ResultSet rs, int row) throws SQLException {
		return new Job(rs.getLong("id"), rs.getLong("oem_id"), JobMode.valueOf(rs.getString("mode")),
				rs.getString("locale"), rs.getInt("limit_value"), Job.LimitUnit.valueOf(rs.getString("limit_unit")),
				Job.ExistingPolicy.valueOf(rs.getString("existing_policy")), rs.getString("options"),
				JobStatus.valueOf(rs.getString("status")), rs.getString("pause_reason"), rs.getString("created_by"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
				rs.getObject("finished_at", OffsetDateTime.class));
	}

}
