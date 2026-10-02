package com.example.oemintel.job;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * @param locale defaults to the OEM's default locale
 * @param limitUnit defaults to FAMILY
 * @param existingPolicy defaults to NEW_ONLY
 * @param options job options (CAD downloads, LLM budget, freshness window, crawl window, PDF caps)
 * @param seedUrls product URLs, required for SEED_LIST
 */
public record CreateJobRequest(@NotBlank String oemCode, @NotNull JobMode mode, String locale,
		@Positive int limitValue, Job.LimitUnit limitUnit, Job.ExistingPolicy existingPolicy,
		Map<String, Object> options, List<String> seedUrls) {

	public Job.LimitUnit limitUnitOrDefault() {
		return (this.limitUnit != null) ? this.limitUnit : Job.LimitUnit.FAMILY;
	}

	public Job.ExistingPolicy existingPolicyOrDefault() {
		return (this.existingPolicy != null) ? this.existingPolicy : Job.ExistingPolicy.NEW_ONLY;
	}

}
