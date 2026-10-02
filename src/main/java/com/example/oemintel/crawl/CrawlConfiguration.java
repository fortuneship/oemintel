package com.example.oemintel.crawl;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FetchProperties.class)
class CrawlConfiguration {

	/**
	 * Redirects are followed manually by {@link PageFetcher} so robots.txt and scope are re-checked on every hop.
	 */
	@Bean
	HttpClient crawlHttpClient(FetchProperties properties) {
		return HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

}
