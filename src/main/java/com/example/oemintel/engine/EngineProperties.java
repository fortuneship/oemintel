package com.example.oemintel.engine;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("oemintel.engine")
public record EngineProperties(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration pollInterval,
		@DefaultValue("30s") Duration reaperInterval, @DefaultValue("10s") Duration evaluatorInterval,
		@DefaultValue("15m") Duration stallAfter, @DefaultValue("20s") Duration shutdownGrace,
		@DefaultValue Lanes lanes, @DefaultValue Retry retry) {

	public record Lanes(@DefaultValue("4") int networkConcurrency, @DefaultValue("4") int localConcurrency,
			@DefaultValue("3") int llmConcurrency) {

		public int concurrency(Lane lane) {
			return switch (lane) {
				case NETWORK -> this.networkConcurrency;
				case LOCAL -> this.localConcurrency;
				case LLM -> this.llmConcurrency;
			};
		}

	}

	public record Retry(@DefaultValue("30s") Duration baseDelay, @DefaultValue("30m") Duration maxDelay,
			@DefaultValue("0.2") double jitter, @DefaultValue("15m") Duration hostBlockedDelay) {
	}

}
