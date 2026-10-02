package com.example.oemintel.crawl;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.oemintel.engine.HostGate;

/**
 * Fixed spacing per host with jitter and adaptive backoff (AD-07). The engine asks {@link #hostsNotReady()} before
 * leasing network work (AD-08); the fetcher then calls {@link #acquire} immediately before each request, which only
 * sleeps if two requests race for the same slot.
 * <p>
 * Pacing state lives in memory: correct for the single application instance this system runs as (AD-06). Blocks and
 * pauses are persisted in crawl_host so they survive restarts and need a person to clear.
 */
@Component
public class PoliteHostGate implements HostGate {

	private static final Logger log = LoggerFactory.getLogger(PoliteHostGate.class);

	/** Successes needed before a raised delay is halved back towards the configured floor. */
	private static final int RECOVERY_SUCCESSES = 50;

	private static final Duration BLOCKED_CACHE = Duration.ofSeconds(5);

	private final FetchProperties properties;

	private final JdbcClient jdbc;

	private final Map<String, HostState> hosts = new ConcurrentHashMap<>();

	private volatile Set<String> blockedHosts = Set.of();

	private volatile Instant blockedLoadedAt = Instant.EPOCH;

	public PoliteHostGate(FetchProperties properties, JdbcClient jdbc) {
		this.properties = properties;
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> hostsNotReady() {
		Instant now = Instant.now();
		Set<String> notReady = new HashSet<>(blockedHosts(now));
		this.hosts.forEach((host, state) -> {
			if (state.nextAllowed().isAfter(now)) {
				notReady.add(host);
			}
		});
		return notReady;
	}

	/**
	 * Reserves the next request slot for the host and waits for it.
	 * @param minimumDelay the robots.txt crawl delay, if larger than the configured spacing
	 */
	public void acquire(String host, Duration minimumDelay) throws InterruptedException {
		long waitMillis;
		synchronized (this) {
			HostState state = this.hosts.computeIfAbsent(host, (h) -> HostState.initial(this.properties.defaultDelay()));
			Instant now = Instant.now();
			Instant slot = state.nextAllowed().isAfter(now) ? state.nextAllowed() : now;
			Duration spacing = jittered(max(state.delay(), minimumDelay));
			this.hosts.put(host, state.withNextAllowed(slot.plus(spacing)));
			waitMillis = Duration.between(now, slot).toMillis();
		}
		if (waitMillis > 0) {
			Thread.sleep(waitMillis);
		}
	}

	public void recordSuccess(String host) {
		this.hosts.computeIfPresent(host, (h, state) -> {
			int successes = state.successes() + 1;
			if (successes >= RECOVERY_SUCCESSES && state.delay().compareTo(this.properties.defaultDelay()) > 0) {
				return new HostState(state.nextAllowed(), max(this.properties.defaultDelay(), state.delay().dividedBy(2)), 0);
			}
			return new HostState(state.nextAllowed(), state.delay(), successes);
		});
	}

	@Override
	public void reportRateLimited(String host, Duration retryAfter) {
		HostState updated = this.hosts.compute(host, (h, state) -> {
			HostState current = (state != null) ? state : HostState.initial(this.properties.defaultDelay());
			Duration delay = min(current.delay().multipliedBy(2), this.properties.maxDelay());
			Duration pause = (retryAfter != null) ? max(retryAfter, delay) : delay;
			return new HostState(Instant.now().plus(pause), delay, 0);
		});
		this.jdbc.sql("update crawl_host set current_delay_ms = :delay, updated_at = now() where host = :host")
			.param("delay", (int) updated.delay().toMillis())
			.param("host", host)
			.update();
		log.warn("{} asked us to slow down: spacing is now {}", host, updated.delay());
	}

	/**
	 * A block means the site does not want this traffic. The host stays blocked until a person resumes it; there is
	 * deliberately no automatic workaround.
	 */
	@Override
	public void reportBlocked(String host, String reason) {
		this.jdbc.sql("""
				update crawl_host set status = 'BLOCKED', block_reason = :reason, updated_at = now() where host = :host
				""").param("reason", reason).param("host", host).update();
		this.blockedLoadedAt = Instant.EPOCH;
		log.error("{} is blocking our requests ({}). Crawling it is paused until resumed by a person.", host, reason);
	}

	private Set<String> blockedHosts(Instant now) {
		if (Duration.between(this.blockedLoadedAt, now).compareTo(BLOCKED_CACHE) > 0) {
			this.blockedHosts = Set.copyOf(this.jdbc.sql("select host from crawl_host where status in ('BLOCKED', 'PAUSED')")
				.query(String.class)
				.list());
			this.blockedLoadedAt = now;
		}
		return this.blockedHosts;
	}

	private Duration jittered(Duration delay) {
		double factor = 1 + this.properties.jitter() * (2 * ThreadLocalRandom.current().nextDouble() - 1);
		return Duration.ofMillis(Math.round(delay.toMillis() * factor));
	}

	private static Duration max(Duration a, Duration b) {
		return (b != null && b.compareTo(a) > 0) ? b : a;
	}

	private static Duration min(Duration a, Duration b) {
		return (a.compareTo(b) < 0) ? a : b;
	}

	private record HostState(Instant nextAllowed, Duration delay, int successes) {

		static HostState initial(Duration delay) {
			return new HostState(Instant.EPOCH, delay, 0);
		}

		HostState withNextAllowed(Instant next) {
			return new HostState(next, this.delay, this.successes);
		}

	}

}
