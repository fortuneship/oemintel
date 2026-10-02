package com.example.oemintel.pipeline;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.oemintel.adapter.AdapterRegistry;
import com.example.oemintel.adapter.ExtractedFamily;
import com.example.oemintel.adapter.OemAdapter;
import com.example.oemintel.catalog.CatalogWriter;
import com.example.oemintel.crawl.FetchedResourceRepository;
import com.example.oemintel.engine.TaskContext;
import com.example.oemintel.engine.TaskFailure;
import com.example.oemintel.engine.TaskHandler;
import com.example.oemintel.engine.TaskResult;
import com.example.oemintel.engine.TaskType;
import com.example.oemintel.engine.WorkItem;
import com.example.oemintel.job.CandidateAdmissionService;
import com.example.oemintel.job.Job;
import com.example.oemintel.job.JobRepository;
import com.example.oemintel.oem.Oem;
import com.example.oemintel.oem.OemRepository;
import com.example.oemintel.storage.BlobStore;

/**
 * Extracts a family from the stored raw page: no network access (AD-14), so it can be re-run at any time from what
 * was fetched.
 */
@Component
@ConditionalOnProperty(name = "oemintel.pipeline.enabled", havingValue = "true", matchIfMissing = true)
class ExtractFamilyHandler implements TaskHandler {

	private final FetchedResourceRepository resources;

	private final BlobStore blobs;

	private final AdapterRegistry adapters;

	private final CatalogWriter catalog;

	private final JobRepository jobs;

	private final OemRepository oems;

	private final CandidateAdmissionService admission;

	private final JsonMapper json;

	ExtractFamilyHandler(FetchedResourceRepository resources, BlobStore blobs, AdapterRegistry adapters,
			CatalogWriter catalog, JobRepository jobs, OemRepository oems, CandidateAdmissionService admission,
			JsonMapper json) {
		this.resources = resources;
		this.blobs = blobs;
		this.adapters = adapters;
		this.catalog = catalog;
		this.jobs = jobs;
		this.oems = oems;
		this.admission = admission;
		this.json = json;
	}

	@Override
	public TaskType taskType() {
		return TaskType.EXTRACT_FAMILY;
	}

	@Override
	public TaskResult handle(WorkItem item, TaskContext context) {
		JsonNode payload = this.json.readTree(item.payload());
		long candidateId = payload.path("candidateId").asLong();
		long resourceId = payload.path("resourceId").asLong();
		Job job = this.jobs.findById(item.jobId()).orElseThrow();
		Oem oem = this.oems.findById(job.oemId()).orElseThrow();
		OemAdapter adapter = this.adapters.adapterFor(oem);
		FetchedResourceRepository.StoredResource resource = this.resources.findById(resourceId)
			.orElseThrow(() -> TaskFailure.permanent("Fetched resource %d no longer exists".formatted(resourceId)));
		String html = new String(this.blobs.get(resource.storageKey()), StandardCharsets.UTF_8);
		ExtractedFamily family = adapter.extract(html, resource.finalUrl());
		return TaskResult.committing(() -> {
			CatalogWriter.WriteResult result = this.catalog.write(job.id(), oem.id(), job.locale(), resourceId,
					adapter.extractorVersion(), family);
			this.admission.complete(candidateId, result.familyId(), result.skuCount());
		});
	}

}
