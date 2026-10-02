package com.example.oemintel.job;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CandidateRepository {

	private final JdbcClient jdbc;

	public CandidateRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Registers a discovered product URL. The same URL found again (another category, sitemap and category) merges
	 * into the existing row by adding the new discovery source.
	 */
	public void upsert(long jobId, String url, String normalizedUrl, String orderKey, String source) {
		this.jdbc.sql("""
				insert into job_candidate (job_id, url, normalized_url, order_key, discovery_sources)
				values (:jobId, :url, :normalizedUrl, :orderKey, array[:source])
				on conflict (job_id, normalized_url) do update
				   set discovery_sources = case
				           when :source = any(job_candidate.discovery_sources) then job_candidate.discovery_sources
				           else array_append(job_candidate.discovery_sources, :source) end,
				       updated_at = now()
				""")
			.param("jobId", jobId)
			.param("url", url)
			.param("normalizedUrl", normalizedUrl)
			.param("orderKey", orderKey)
			.param("source", source)
			.update();
	}

	/** Takes the next {@code count} admissible candidates in deterministic order and marks them ADMITTED. */
	List<AdmittedCandidate> admitNext(long jobId, int count, boolean includeExisting) {
		return this.jdbc.sql("""
				update job_candidate c
				   set status = 'ADMITTED', updated_at = now()
				 where c.id in (
				       select x.id from job_candidate x
				        where x.job_id = :jobId
				          and x.status = 'DISCOVERED'
				          and (:includeExisting or x.matched_family_id is null)
				          and not exists (select 1 from product_family f
				                           where f.id = x.matched_family_id and f.review_status = 'REJECTED')
				        order by x.order_key, x.id
				        limit :count
				          for update skip locked)
				returning c.id, c.url, c.normalized_url, c.order_key
				""")
			.param("jobId", jobId)
			.param("count", count)
			.param("includeExisting", includeExisting)
			.query((rs, row) -> new AdmittedCandidate(rs.getLong("id"), rs.getString("url"),
					rs.getString("normalized_url"), rs.getString("order_key")))
			.list();
	}

	int countByStatus(long jobId, String... statuses) {
		return this.jdbc.sql("""
				select count(*) from job_candidate
				 where job_id = :jobId and status = any(string_to_array(:statuses, ','))
				""").param("jobId", jobId).param("statuses", String.join(",", statuses)).query(Integer.class).single();
	}

	long completedSkuCount(long jobId) {
		return this.jdbc.sql("""
				select coalesce(sum(sku_count), 0) from job_candidate where job_id = :jobId and status = 'COMPLETED'
				""").param("jobId", jobId).query(Long.class).single();
	}

	/** @return the job id, if the candidate was still ADMITTED */
	java.util.Optional<Long> close(long candidateId, String status, String reason, Long familyId, Integer skuCount) {
		return this.jdbc.sql("""
				update job_candidate
				   set status = :status, reason = :reason, family_id = coalesce(:familyId, family_id),
				       sku_count = coalesce(:skuCount, sku_count), updated_at = now()
				 where id = :id and status = 'ADMITTED'
				returning job_id
				""")
			.param("status", status)
			.param("reason", reason)
			.param("familyId", familyId)
			.param("skuCount", skuCount)
			.param("id", candidateId)
			.query(Long.class)
			.optional();
	}

	record AdmittedCandidate(long id, String url, String normalizedUrl, String orderKey) {
	}

}
