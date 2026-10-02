package com.example.oemintel.crawl;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.oemintel.TestcontainersConfiguration;
import com.example.oemintel.engine.ErrorClass;
import com.example.oemintel.engine.TaskFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 4 acceptance for the parts built in the thin slice, against a local HTTP server. */
@SpringBootTest(properties = { "oemintel.engine.enabled=false", "oemintel.fetch.default-delay=300ms",
		"oemintel.fetch.jitter=0", "oemintel.storage.root=target/test-blobs" })
@Import(TestcontainersConfiguration.class)
class PageFetcherTests {

	private static HttpServer server;

	private static final List<Request> requests = new CopyOnWriteArrayList<>();

	private static final Set<String> HOSTS = Set.of("localhost");

	@Autowired
	PageFetcher fetcher;

	@Autowired
	JdbcClient jdbc;

	long oemId;

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", PageFetcherTests::handle);
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@BeforeEach
	void reset() {
		requests.clear();
		this.oemId = this.jdbc.sql("select id from oem where code = 'ROXTEC'").query(Long.class).single();
		this.jdbc.sql("delete from fetched_resource where normalized_url like 'http://localhost%'").update();
	}

	@Test
	void disallowedPathsAreNeverRequested() {
		assertThatThrownBy(() -> this.fetcher.fetch(this.oemId, null, url("/private/secret"), HOSTS))
			.isInstanceOfSatisfying(TaskFailure.class, (f) -> assertThat(f.errorClass()).isEqualTo(ErrorClass.SKIPPED));
		assertThat(requests).extracting(Request::path).doesNotContain("/private/secret");
	}

	@Test
	void requestsToOneHostAreSpacedByTheConfiguredDelay() throws Exception {
		this.fetcher.fetch(this.oemId, null, url("/page/1"), HOSTS);
		this.fetcher.fetch(this.oemId, null, url("/page/2"), HOSTS);
		this.fetcher.fetch(this.oemId, null, url("/page/3"), HOSTS);
		List<Request> pages = requests.stream().filter((r) -> r.path().startsWith("/page/")).toList();
		assertThat(pages).hasSize(3);
		for (int i = 1; i < pages.size(); i++) {
			assertThat(pages.get(i).atMillis() - pages.get(i - 1).atMillis()).isGreaterThanOrEqualTo(290);
		}
	}

	@Test
	void aFreshStoredCopyIsReusedWithoutContactingTheSite() throws Exception {
		PageFetcher.FetchedPage first = this.fetcher.fetch(this.oemId, null, url("/page/fresh"), HOSTS);
		PageFetcher.FetchedPage second = this.fetcher.fetch(this.oemId, null, url("/page/fresh"), HOSTS);
		assertThat(first.fromCache()).isFalse();
		assertThat(second.fromCache()).isTrue();
		assertThat(second.text()).isEqualTo(first.text());
		assertThat(requests).filteredOn((r) -> r.path().equals("/page/fresh")).hasSize(1);
	}

	@Test
	void responsesMapOntoRetryClasses() {
		assertFailure("/missing", ErrorClass.PERMANENT);
		assertFailure("/slow-down", ErrorClass.RATE_LIMITED);
		assertFailure("/challenge", ErrorClass.HOST_BLOCKED);
		assertFailure("/broken", ErrorClass.TRANSIENT);
		assertThatThrownBy(() -> this.fetcher.fetch(this.oemId, null, "https://elsewhere.test/x", HOSTS))
			.isInstanceOfSatisfying(TaskFailure.class, (f) -> assertThat(f.errorClass()).isEqualTo(ErrorClass.SKIPPED));
	}

	@Test
	void redirectsAreFollowedAndRecorded() throws Exception {
		PageFetcher.FetchedPage page = this.fetcher.fetch(this.oemId, null, url("/old"), HOSTS);
		assertThat(page.finalUrl()).endsWith("/page/new");
		assertThat(this.jdbc.sql("select redirect_chain::text from fetched_resource where id = :id")
			.param("id", page.resourceId())
			.query(String.class)
			.single()).contains("/old");
	}

	private void assertFailure(String path, ErrorClass expected) {
		assertThatThrownBy(() -> this.fetcher.fetch(this.oemId, null, url(path), HOSTS))
			.isInstanceOfSatisfying(TaskFailure.class, (f) -> assertThat(f.errorClass()).isEqualTo(expected));
	}

	private static String url(String path) {
		return "http://localhost:" + server.getAddress().getPort() + path;
	}

	private static void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		requests.add(new Request(path, System.currentTimeMillis()));
		switch (path) {
			case "/robots.txt" -> respond(exchange, 200, "User-agent: *\nDisallow: /private/\n");
			case "/missing" -> respond(exchange, 404, "not found");
			case "/slow-down" -> {
				exchange.getResponseHeaders().add("Retry-After", "1");
				respond(exchange, 429, "too many requests");
			}
			case "/challenge" -> respond(exchange, 403, "<html><title>Just a moment...</title></html>");
			case "/broken" -> respond(exchange, 502, "bad gateway");
			case "/old" -> {
				exchange.getResponseHeaders().add("Location", "/page/new");
				respond(exchange, 301, "");
			}
			default -> respond(exchange, 200, "<html><body>" + path + "</body></html>");
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, (bytes.length == 0) ? -1 : bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	record Request(String path, long atMillis) {
	}

}
