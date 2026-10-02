package com.example.oemintel.adapter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.oemintel.crawl.UrlNormalizer;
import com.example.oemintel.oem.Oem;

@Component
public class AdapterRegistry {

	private final Map<String, OemAdapter> adapters;

	public AdapterRegistry(List<OemAdapter> adapters) {
		this.adapters = adapters.stream().collect(Collectors.toMap(OemAdapter::key, Function.identity()));
	}

	public OemAdapter adapterFor(Oem oem) {
		OemAdapter adapter = this.adapters.get(oem.adapterKey());
		if (adapter == null) {
			throw new IllegalStateException("No adapter '%s' for OEM %s".formatted(oem.adapterKey(), oem.code()));
		}
		return adapter;
	}

	/** The scope allowlist: the OEM's own site. CDN hosts are added when assets are downloaded (Step 7). */
	public Set<String> allowedHosts(Oem oem) {
		return Set.of(UrlNormalizer.host(oem.baseUrl()));
	}

}
