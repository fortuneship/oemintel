package com.example.oemintel.engine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls one lane: leases as many items as the lane has free capacity and hands them to its executor. One
 * dispatcher per lane, called from a single scheduler thread, so leasing within a lane never races itself.
 */
class LaneDispatcher {

	private static final Logger log = LoggerFactory.getLogger(LaneDispatcher.class);

	private final Lane lane;

	private final int capacity;

	private final ExecutorService executor;

	private final String owner;

	private final HandlerRegistry registry;

	private final WorkItemRepository items;

	private final TaskRunner runner;

	private final HostGate hostGate;

	private final AtomicInteger inFlight = new AtomicInteger();

	LaneDispatcher(Lane lane, int capacity, ExecutorService executor, String owner, HandlerRegistry registry,
			WorkItemRepository items, TaskRunner runner, HostGate hostGate) {
		this.lane = lane;
		this.capacity = capacity;
		this.executor = executor;
		this.owner = owner;
		this.registry = registry;
		this.items = items;
		this.runner = runner;
		this.hostGate = hostGate;
	}

	void poll() {
		try {
			int free = this.capacity - this.inFlight.get();
			Set<TaskType> types = this.registry.typesFor(this.lane);
			if (free <= 0 || types.isEmpty()) {
				return;
			}
			if (this.lane == Lane.NETWORK) {
				Set<String> notReady = this.hostGate.hostsNotReady();
				for (int i = 0; i < free; i++) {
					Optional<WorkItem> item = this.items.leaseNetwork(types, notReady, this.owner);
					if (item.isEmpty()) {
						break;
					}
					submit(item.get());
				}
			}
			else {
				List<WorkItem> leased = this.items.lease(this.lane, types, free, this.owner);
				leased.forEach(this::submit);
			}
		}
		catch (RuntimeException ex) {
			// Database briefly unavailable, for example. State lives in the database, so the next poll simply retries.
			log.warn("Polling lane {} failed: {}", this.lane, ex.getMessage());
		}
	}

	private void submit(WorkItem item) {
		this.inFlight.incrementAndGet();
		this.executor.execute(() -> {
			try {
				this.runner.run(item);
			}
			finally {
				this.inFlight.decrementAndGet();
			}
		});
	}

	int inFlight() {
		return this.inFlight.get();
	}

}
