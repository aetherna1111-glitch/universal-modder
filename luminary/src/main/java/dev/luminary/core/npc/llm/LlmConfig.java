package dev.luminary.core.npc.llm;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provider settings. The API key never comes from a config file: {@link #apiKey} is read from the environment by the
 * Fabric layer and passed in here. Endpoints are restricted to {@code http}/{@code https} and to loopback unless
 * the host is on {@code allowedHosts}.
 */
public record LlmConfig(String provider, URI endpoint, String model, Duration connectTimeout, Duration requestTimeout,
						int maxResponseBytes, int maxConcurrent, Set<String> allowedHosts, Optional<String> apiKey,
						Duration keepAlive, int contextTokens) {
	/** Pre-slice-13 shape. */
	public LlmConfig(String provider, URI endpoint, String model, Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
					 int maxConcurrent, Set<String> allowedHosts, Optional<String> apiKey, Duration keepAlive) {
		this(provider, endpoint, model, connectTimeout, requestTimeout, maxResponseBytes, maxConcurrent, allowedHosts, apiKey, keepAlive, 4096);
	}
	public static final String PROVIDER_NONE = "none";
	public static final String PROVIDER_OLLAMA = "ollama";
	public static final String PROVIDER_OPENAI = "openai";
	public static final String ENV_API_KEY = "LUMINARY_LLM_API_KEY";

	public static final LlmConfig DISABLED = new LlmConfig(PROVIDER_NONE, URI.create("http://127.0.0.1:11434"), "qwen2.5:7b",
			Duration.ofSeconds(2), Duration.ofSeconds(20), 64 * 1024, 2, Set.of(), Optional.empty(), Duration.ofMinutes(5));

	public LlmConfig {
		Objects.requireNonNull(provider);
		Objects.requireNonNull(endpoint);
		Objects.requireNonNull(model);
		Objects.requireNonNull(connectTimeout);
		Objects.requireNonNull(requestTimeout);
		allowedHosts = Set.copyOf(allowedHosts);
		Objects.requireNonNull(apiKey);
		Objects.requireNonNull(keepAlive);
		if (maxResponseBytes < 1024 || maxConcurrent < 1) {
			throw new IllegalArgumentException("maxResponseBytes >= 1024 and maxConcurrent >= 1 required");
		}
		String scheme = endpoint.getScheme();
		if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || endpoint.getHost() == null) {
			throw new IllegalArgumentException("endpoint must be an http(s) URL with a host: " + endpoint);
		}
	}

	public boolean endpointAllowed() {
		String host = endpoint.getHost();
		return isLoopback(host) || allowedHosts.contains(host.toLowerCase());
	}

	static boolean isLoopback(String host) {
		String h = host.toLowerCase();
		return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]") || h.startsWith("127.");
	}

	public LlmConfig withProvider(String p) {
		return new LlmConfig(p, endpoint, model, connectTimeout, requestTimeout, maxResponseBytes, maxConcurrent, allowedHosts, apiKey, keepAlive, contextTokens);
	}

	public LlmConfig withEndpoint(URI e) {
		return new LlmConfig(provider, e, model, connectTimeout, requestTimeout, maxResponseBytes, maxConcurrent, allowedHosts, apiKey, keepAlive, contextTokens);
	}

	public LlmConfig withRequestTimeout(Duration t) {
		return new LlmConfig(provider, endpoint, model, connectTimeout, t, maxResponseBytes, maxConcurrent, allowedHosts, apiKey, keepAlive, contextTokens);
	}

	public LlmConfig withMaxResponseBytes(int b) {
		return new LlmConfig(provider, endpoint, model, connectTimeout, requestTimeout, b, maxConcurrent, allowedHosts, apiKey, keepAlive, contextTokens);
	}

	public LlmConfig withMaxConcurrent(int c) {
		return new LlmConfig(provider, endpoint, model, connectTimeout, requestTimeout, maxResponseBytes, c, allowedHosts, apiKey, keepAlive, contextTokens);
	}

	public LlmConfig withApiKey(Optional<String> key) {
		return new LlmConfig(provider, endpoint, model, connectTimeout, requestTimeout, maxResponseBytes, maxConcurrent, allowedHosts, key, keepAlive);
	}

	public LlmConfig withAllowedHosts(Set<String> hosts) {
		return new LlmConfig(provider, endpoint, model, connectTimeout, requestTimeout, maxResponseBytes, maxConcurrent, hosts, apiKey, keepAlive);
	}
}
