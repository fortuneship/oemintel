package com.example.oemintel.engine;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.SmartLifecycle;

/**
 * Starts the lane dispatchers and housekeeping tickers after the context is ready, and on shutdown stops
 * leasing, gives in-flight work a grace period, then exits. Anything still running when the grace period ends
 * is retried by another start once its lease expires.
 */
public class EngineLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(EngineLifecycle.class);

	private final EngineProperties properties;

	private final HandlerRegistry registry;

	private final WorkItemRepository items;

	private final TaskRunner runner;

	private final HostGate hostGate;

	private final List<EngineTicker> tickers;

	private final List<ExecutorService> laneExecutors = new ArrayList<>();

	private ScheduledExecutorService scheduler;

	private volatile boolean running;

	public EngineLifecycle(EngineProperties properties, HandlerRegistry registry, WorkItemRepository items,
			TaskRunner runner, HostGate hostGate, List<EngineTicker> tickers) {
		this.properties = properties;
		this.registry = registry;
		this.items = items;
		this.runner = runner;
		this.hostGate = hostGate;
		this.tickers = tickers;
	}

	@Override
	public synchronized void start() {
		String owner = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);
		this.scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("engine-scheduler-", 0).factory());
		long pollMillis = this.properties.pollInterval().toMillis();
		for (Lane lane : Lane.values()) {
			ExecutorService executor = executorFor(lane);
			this.laneExecutors.add(executor);
			LaneDispatcher dispatcher = new LaneDispatcher(lane, this.properties.lanes().concurrency(lane), executor,
					owner, this.registry, this.items, this.runner, this.hostGate);
			this.scheduler.scheduleWithFixedDelay(dispatcher::poll, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
		}
		for (EngineTicker ticker : this.tickers) {
			long millis = ticker.interval().toMillis();
			this.scheduler.scheduleWithFixedDelay(() -> safeTick(ticker), millis, millis, TimeUnit.MILLISECONDS);
		}
		this.running = true;
		log.info("Engine started as {}", owner);
	}

	/** I/O-bound lanes use virtual threads; the LOCAL lane is CPU-bound (parsing), so it gets platform threads. */
	private ExecutorService executorFor(Lane lane) {
		String prefix = "engine-" + lane.name().toLowerCase() + "-";
		return switch (lane) {
			case NETWORK, LLM -> Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
			case LOCAL -> Executors.newFixedThreadPool(this.properties.lanes().concurrency(lane),
					Thread.ofPlatform().name(prefix, 0).factory());
		};
	}

	private void safeTick(EngineTicker ticker) {
		try {
			ticker.tick();
		}
		catch (RuntimeException ex) {
			log.warn("{} failed: {}", ticker.getClass().getSimpleName(), ex.getMessage(), ex);
		}
	}

	@Override
	public synchronized void stop() {
		if (!this.running) {
			return;
		}
		this.running = false;
		this.scheduler.shutdownNow();
		this.laneExecutors.forEach(ExecutorService::shutdown);
		long deadline = System.nanoTime() + this.properties.shutdownGrace().toNanos();
		for (ExecutorService executor : this.laneExecutors) {
			try {
				long remaining = deadline - System.nanoTime();
				if (!executor.awaitTermination(Math.max(0, remaining), TimeUnit.NANOSECONDS)) {
					executor.shutdownNow();
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				executor.shutdownNow();
			}
		}
		this.laneExecutors.clear();
		log.info("Engine stopped");
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

}
