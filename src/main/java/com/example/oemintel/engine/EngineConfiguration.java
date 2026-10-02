package com.example.oemintel.engine;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineProperties.class)
class EngineConfiguration {

	/** Disabled with {@code oemintel.engine.enabled=false}, e.g. to run only the dashboard or in repository tests. */
	@Bean
	@ConditionalOnProperty(name = "oemintel.engine.enabled", havingValue = "true", matchIfMissing = true)
	EngineLifecycle engineLifecycle(EngineProperties properties, HandlerRegistry registry, WorkItemRepository items,
			TaskRunner runner, HostGate hostGate, List<EngineTicker> tickers) {
		return new EngineLifecycle(properties, registry, items, runner, hostGate, tickers);
	}

}
