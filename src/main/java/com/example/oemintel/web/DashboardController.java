package com.example.oemintel.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.oemintel.job.CreateJobRequest;
import com.example.oemintel.job.Job;
import com.example.oemintel.job.JobException;
import com.example.oemintel.job.JobMode;
import com.example.oemintel.job.JobProgressQuery;
import com.example.oemintel.job.JobService;
import com.example.oemintel.job.JobStatus;

/**
 * Minimal server-rendered job screens for the thin slice. The full dashboard (htmx, security, roles) is Step 11;
 * until then the app binds to localhost only.
 */
@Controller
class DashboardController {

	private static final String ACTOR = "dashboard";

	private final JobService jobs;

	private final JobProgressQuery progress;

	private final String sampleSeedUrls;

	DashboardController(JobService jobs, JobProgressQuery progress) throws IOException {
		this.jobs = jobs;
		this.progress = progress;
		try (InputStream in = new ClassPathResource("adapters/roxtec/sample-families.txt").getInputStream()) {
			this.sampleSeedUrls = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}

	@GetMapping("/")
	String home(Model model) {
		model.addAttribute("jobs", this.progress.recentJobs(20));
		if (!model.containsAttribute("seedUrls")) {
			model.addAttribute("seedUrls", this.sampleSeedUrls);
		}
		model.addAttribute("maxUnauthorised", JobService.UNAUTHORISED_FAMILY_LIMIT);
		return "home";
	}

	@PostMapping("/jobs")
	String createSeedJob(@RequestParam String seedUrls, RedirectAttributes redirect) {
		List<String> urls = Arrays.stream(seedUrls.split("\\R")).map(String::trim).filter((u) -> !u.isEmpty()).toList();
		try {
			Job job = this.jobs.create(new CreateJobRequest("ROXTEC", JobMode.SEED_LIST, null, Math.max(1, urls.size()),
					Job.LimitUnit.FAMILY, null, null, urls), ACTOR);
			return "redirect:/jobs/" + job.id();
		}
		catch (JobException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			redirect.addFlashAttribute("seedUrls", seedUrls);
			return "redirect:/";
		}
	}

	@GetMapping("/jobs/{id}")
	String job(@PathVariable long id, Model model) {
		Job job = this.jobs.find(id).orElseThrow(() -> JobException.notFound("No job " + id));
		model.addAttribute("job", job);
		model.addAttribute("active", JobStatus.ACTIVE.contains(job.status()));
		model.addAttribute("progress", this.progress.progress(id));
		model.addAttribute("candidates", this.progress.candidates(id));
		model.addAttribute("errors", this.progress.errors(id));
		return "job";
	}

	@PostMapping("/jobs/{id}/cancel")
	String cancel(@PathVariable long id, RedirectAttributes redirect) {
		try {
			this.jobs.cancel(id, ACTOR);
		}
		catch (JobException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/jobs/" + id;
	}

}
