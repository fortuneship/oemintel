package com.example.oemintel.crawl;

import java.time.Duration;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The raw layer's index of fetches. Rows are only ever inserted. */
@Repository
public class FetchedResourceRepository {

	private final JdbcClient jdbc;

	public FetchedResourceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long insert(NewResource resource) {
		return this.jdbc.sql("""
				insert into fetched_resource (oem_id, job_id, requested_url, normalized_url, final_url, http_status,
				                              content_type, content_hash, storage_key, fetch_mode, classification,
				                              redirect_chain, etag, last_modified, byte_size, duration_ms)
				values (:oemId, :jobId, :requestedUrl, :normalizedUrl, :finalUrl, :httpStatus, :contentType,
				        :contentHash, :storageKey, 'HTTP', :classification, cast(:redirectChain as jsonb), :etag,
				        :lastModified, :byteSize, :durationMs)
				returning id
				""")
			.param("oemId", resource.oemId())
			.param("jobId", resource.jobId())
			.param("requestedUrl", resource.requestedUrl())
			.param("normalizedUrl", resource.normalizedUrl())
			.param("finalUrl", resource.finalUrl())
			.param("httpStatus", resource.httpStatus())
			.param("contentType", resource.contentType())
			.param("contentHash", resource.contentHash())
			.param("storageKey", resource.storageKey())
			.param("classification", resource.classification())
			.param("redirectChain", resource.redirectChainJson())
			.param("etag", resource.etag())
			.param("lastModified", resource.lastModified())
			.param("byteSize", resource.byteSize())
			.param("durationMs", resource.durationMs())
			.query(Long.class)
			.single();
	}

	/** The newest successful fetch of this URL within the freshness window, if any. */
	public Optional<StoredResource> findFresh(String normalizedUrl, Duration freshness) {
		return this.jdbc.sql("""
				select id, final_url, storage_key, content_hash from fetched_resource
				 where normalized_url = :url and classification = 'OK' and storage_key is not null
				   and fetched_at > now() - make_interval(secs => :seconds)
				 order by fetched_at desc limit 1
				""")
			.param("url", normalizedUrl)
			.param("seconds", (double) freshness.toSeconds())
			.query((rs, row) -> new StoredResource(rs.getLong("id"), rs.getString("final_url"),
					rs.getString("storage_key"), rs.getString("content_hash")))
			.optional();
	}

	public Optional<StoredResource> findById(long id) {
		return this.jdbc.sql("select id, final_url, storage_key, content_hash from fetched_resource where id = :id")
			.param("id", id)
			.query((rs, row) -> new StoredResource(rs.getLong("id"), rs.getString("final_url"),
					rs.getString("storage_key"), rs.getString("content_hash")))
			.optional();
	}

	public record NewResource(long oemId, Long jobId, String requestedUrl, String normalizedUrl, String finalUrl,
			Integer httpStatus, String contentType, String contentHash, String storageKey, String classification,
			String redirectChainJson, String etag, String lastModified, Long byteSize, int durationMs) {
	}

	public record StoredResource(long id, String finalUrl, String storageKey, String contentHash) {
	}

}
