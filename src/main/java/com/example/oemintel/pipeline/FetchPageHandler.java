package com.example.oemintel.pipeline;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.oemintel.adapter.AdapterRegistry;
import com.example.oemintel.adapter.OemAdapter;
import com.example.oemintel.crawl.PageFetcher;
import com.example.oemintel.crawl.UrlNormalizer;
import com.example.oemintel.engine.ErrorClass;
import com.example.oemintel.engine.NewWorkItem;
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

/**
 * Fetches an admitted candidate's page and checks it is a product family page. Valid pages go on to extraction as a
 * separate work item; pages that are not products, missing, or disallowed release their admission slot.
 */
@Component
@ConditionalOnProperty(name = "oemintel.pipeline.enabled", havingValue = "true", matchIfMissing = true)
class FetchPageHandler implements TaskHandler {

	private final PageFetcher fetcher;

	private final AdapterRegistry adapters;

	private final JobRepository jobs;

	private final OemRepository oems;

	private final CandidateAdmissionService admission;

	private final JsonMapper json;

	FetchPageHandler(PageFetcher fetcher, AdapterRegistry adapters, JobRepository jobs, OemRepository oems,
			CandidateAdmissionService admission, JsonMapper json) {
		this.fetcher = fetcher;
		this.adapters = adapters;
		this.jobs = jobs;
		this.oems = oems;
		this.admission = admission;
		this.json = json;
	}

	@Override
	public TaskType taskType() {
		return TaskType.FETCH_PAGE;
	}

	@Override
	public TaskResult handle(WorkItem item, TaskContext context) throws Exception {
		long candidateId = this.json.readTree(item.payload()).path("candidateId").asLong();
		Job job = this.jobs.findById(item.jobId()).orElseThrow();
		Oem oem = this.oems.findById(job.oemId()).orElseThrow();
		OemAdapter adapter = this.adapters.adapterFor(oem);

		PageFetcher.FetchedPage page;
		try {
			page = this.fetcher.fetch(oem.id(), job.id(), item.target(), this.adapters.allowedHosts(oem));
		}
		catch (TaskFailure failure) {
			if (failure.errorClass() == ErrorClass.PERMANENT || failure.errorClass() == ErrorClass.SKIPPED) {
				return TaskResult.committing(() -> this.admission.reject(candidateId, false, failure.getMessage()));
			}
			throw failure;
		}

		OemAdapter.PageCheck check = adapter.check(page.text(), page.finalUrl());
		if (!check.isFamily()) {
			return TaskResult.committing(() -> this.admission.reject(candidateId, false, check.reason()));
		}
		String canonical = UrlNormalizer.normalize(check.canonicalUrl()).orElse(page.finalUrl());
		NewWorkItem extract = NewWorkItem.of(TaskType.EXTRACT_FAMILY, canonical, canonical)
			.withPayload(this.json.writeValueAsString(Map.of("candidateId", candidateId, "resourceId", page.resourceId())));
		return TaskResult.withChildren(List.of(extract));
	}

}
