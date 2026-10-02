package com.example.oemintel.engine;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
class LeaseReaper implements EngineTicker {

	private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

	private final WorkItemRepository items;

	private final Duration interval;

	LeaseReaper(WorkItemRepository items, EngineProperties properties) {
		this.items = items;
		this.interval = properties.reaperInterval();
	}

	@Override
	public Duration interval() {
		return this.interval;
	}

	@Override
	public void tick() {
		int reaped = this.items.reapExpiredLeases();
		if (reaped > 0) {
			log.warn("Returned {} item(s) with expired leases to the queue", reaped);
		}
	}

}
