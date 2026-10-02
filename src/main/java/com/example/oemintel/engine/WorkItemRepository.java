package com.example.oemintel.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The queue (AD-02). All times come from the database clock so lease expiry never depends on JVM clocks.
 * Lists are passed as comma-joined text and split in SQL: task types and host names never contain commas.
 */
@Repository
public class WorkItemRepository {

	private static final String ACTIVE_JOB_STATUSES = "('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED')";

	private static final String LEASABLE_JOB_STATUSES = "('DISCOVERING', 'RUNNING')";

	private static final String LEASE_UPDATE = """
			update work_item w
			   set status = 'LEASED',
			       attempts = w.attempts + 1,
			       lease_owner = :owner,
			       lease_token = gen_random_uuid(),
			       lease_expires_at = now() + make_interval(secs => w.lease_seconds),
			       started_at = coalesce(w.started_at, now()),
			       updated_at = now()
			  from candidate c
			 where w.id = c.id
			returning w.*
			""";

	private final JdbcClient jdbc;

	public WorkItemRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the item unless the same (job, type, dedupe key) exists or the job is no longer active.
	 * @return whether a row was inserted
	 */
	public boolean enqueue(long jobId, Long parentId, NewWorkItem item) {
		TaskType type = item.taskType();
		return this.jdbc.sql("""
				insert into work_item (job_id, task_type, lane, host, target, payload, dedupe_key, parent_id, priority,
				                       max_attempts, lease_seconds)
				select :jobId, :taskType, :lane, cast(:host as text), :target,
				       cast(coalesce(cast(:payload as text), '{}') as jsonb), :dedupeKey,
				       cast(:parentId as bigint), :priority, :maxAttempts, :leaseSeconds
				 where exists (select 1 from onboarding_job where id = :jobId and status in %s)
				on conflict (job_id, task_type, dedupe_key) do nothing
				""".formatted(ACTIVE_JOB_STATUSES))
			.param("jobId", jobId)
			.param("taskType", type.name())
			.param("lane", type.lane().name())
			.param("host", item.host())
			.param("target", item.target())
			.param("payload", item.payload())
			.param("dedupeKey", item.dedupeKey())
			.param("parentId", parentId)
			.param("priority", item.priority())
			.param("maxAttempts", type.maxAttempts())
			.param("leaseSeconds", (int) type.lease().toSeconds())
			.update() == 1;
	}

	/** Leases up to {@code limit} due items of the given types in a non-network lane. */
	public List<WorkItem> lease(Lane lane, Collection<TaskType> types, int limit, String owner) {
		return this.jdbc.sql("""
				with candidate as (
				    select w.id
				      from work_item w
				      join onboarding_job j on j.id = w.job_id
				     where w.lane = :lane
				       and w.status in ('PENDING', 'RETRY_WAIT')
				       and w.next_attempt_at <= now()
				       and w.task_type = any(string_to_array(:types, ','))
				       and j.status in %s
				     order by w.priority desc, w.id
				     limit :limit
				       for update of w skip locked)
				""".formatted(LEASABLE_JOB_STATUSES) + LEASE_UPDATE)
			.param("lane", lane.name())
			.param("types", join(types))
			.param("limit", limit)
			.param("owner", owner)
			.query(WorkItemRepository::mapItem)
			.list();
	}

	/**
	 * Leases one due NETWORK item whose host is ready and has nothing else in flight: one concurrent request per
	 * host (NFR-01), and never a lease that would sit waiting on a rate limit (AD-08).
	 */
	public Optional<WorkItem> leaseNetwork(Collection<TaskType> types, Set<String> hostsNotReady, String owner) {
		return this.jdbc.sql("""
				with candidate as (
				    select w.id
				      from work_item w
				      join onboarding_job j on j.id = w.job_id
				     where w.lane = 'NETWORK'
				       and w.status in ('PENDING', 'RETRY_WAIT')
				       and w.next_attempt_at <= now()
				       and w.task_type = any(string_to_array(:types, ','))
				       and j.status in %s
				       and not (coalesce(w.host, '') = any(string_to_array(:notReady, ',')))
				       and not exists (select 1 from work_item x
				                        where x.status = 'LEASED' and x.host is not distinct from w.host)
				     order by w.priority desc, w.id
				     limit 1
				       for update of w skip locked)
				""".formatted(LEASABLE_JOB_STATUSES) + LEASE_UPDATE)
			.param("types", join(types))
			.param("notReady", String.join(",", hostsNotReady))
			.param("owner", owner)
			.query(WorkItemRepository::mapItem)
			.optional();
	}

	/** Marks the item done if, and only if, it is still leased under this token (fencing). */
	public boolean markDone(long id, UUID leaseToken) {
		return this.jdbc.sql("""
				update work_item
				   set status = 'DONE', finished_at = now(), updated_at = now(),
				       lease_owner = null, lease_token = null, lease_expires_at = null, error_class = null
				 where id = :id and status = 'LEASED' and lease_token = :token
				""").param("id", id).param("token", leaseToken).update() == 1;
	}

	/** Applies a retry decision to a failed attempt, again only under the current lease token. */
	public boolean reschedule(long id, UUID leaseToken, ItemStatus status, Duration delay, boolean refundAttempt,
			ErrorClass errorClass, String error) {
		return this.jdbc.sql("""
				update work_item
				   set status = :status,
				       next_attempt_at = now() + make_interval(secs => :delaySeconds),
				       attempts = attempts - case when :refund then 1 else 0 end,
				       finished_at = case when :status in ('DEAD', 'SKIPPED') then now() end,
				       lease_owner = null, lease_token = null, lease_expires_at = null,
				       error_class = :errorClass, last_error = :error, updated_at = now()
				 where id = :id and status = 'LEASED' and lease_token = :token
				""")
			.param("status", status.name())
			.param("delaySeconds", delay.toMillis() / 1000.0)
			.param("refund", refundAttempt)
			.param("errorClass", errorClass.name())
			.param("error", truncate(error))
			.param("id", id)
			.param("token", leaseToken)
			.update() == 1;
	}

	public boolean extendLease(long id, UUID leaseToken) {
		return this.jdbc.sql("""
				update work_item
				   set lease_expires_at = now() + make_interval(secs => lease_seconds), updated_at = now()
				 where id = :id and status = 'LEASED' and lease_token = :token
				""").param("id", id).param("token", leaseToken).update() == 1;
	}

	/**
	 * Returns expired leases to the queue (a worker died or hung). The attempt was already counted at lease
	 * time, so an item that keeps killing its worker ends up DEAD instead of looping forever.
	 */
	public int reapExpiredLeases() {
		return this.jdbc.sql("""
				update work_item
				   set status = case when attempts >= max_attempts then 'DEAD' else 'RETRY_WAIT' end,
				       finished_at = case when attempts >= max_attempts then now() end,
				       next_attempt_at = now(),
				       lease_owner = null, lease_token = null, lease_expires_at = null,
				       error_class = 'LEASE_EXPIRED', last_error = 'Lease expired before completion',
				       updated_at = now()
				 where status = 'LEASED' and lease_expires_at < now()
				""").update();
	}

	/** Moves waiting items of a cancelled job out of the queue. In-flight items finish; their children are dropped. */
	public int cancelPending(long jobId) {
		return this.jdbc.sql("""
				update work_item set status = 'CANCELLED', finished_at = now(), updated_at = now()
				 where job_id = :jobId and status in ('PENDING', 'RETRY_WAIT')
				""").param("jobId", jobId).update();
	}

	public int retryDead(long jobId) {
		return this.jdbc.sql("""
				update work_item
				   set status = 'PENDING', attempts = 0, next_attempt_at = now(), finished_at = null, updated_at = now()
				 where job_id = :jobId and status = 'DEAD'
				""").param("jobId", jobId).update();
	}

	private static String join(Collection<TaskType> types) {
		return types.stream().map(Enum::name).collect(Collectors.joining(","));
	}

	private static String truncate(String error) {
		return (error == null || error.length() <= 2000) ? error : error.substring(0, 2000);
	}

	static WorkItem mapItem(ResultSet rs, int row) throws SQLException {
		long parent = rs.getLong("parent_id");
		Long parentId = rs.wasNull() ? null : parent;
		return new WorkItem(rs.getLong("id"), rs.getLong("job_id"), TaskType.valueOf(rs.getString("task_type")),
				rs.getString("host"), rs.getString("target"), rs.getString("payload"), rs.getString("dedupe_key"),
				parentId, rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getObject("lease_token", UUID.class));
	}

}
