package dev.luminary.core.npc.llm;

import java.util.concurrent.CompletableFuture;

/** The default: no model configured. Every request fails immediately with UNAVAILABLE so callers fall back. */
public final class NoLlmProvider implements LlmProvider {
	private final LlmStats stats = new LlmStats();

	@Override
	public String id() {
		return LlmConfig.PROVIDER_NONE;
	}

	@Override
	public String model() {
		return "-";
	}

	@Override
	public boolean healthy() {
		return false;
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		stats.request();
		stats.failure();
		CompletableFuture<LlmResponse> f = new CompletableFuture<>();
		f.completeExceptionally(new LlmException(LlmException.Kind.UNAVAILABLE, "no language model is configured (llm.provider = none)"));
		return f;
	}

	@Override
	public LlmStats stats() {
		return stats;
	}
}
