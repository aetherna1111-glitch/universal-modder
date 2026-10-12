package dev.luminary.core.npc.llm;

import java.util.concurrent.CompletableFuture;

/**
 * A language-model backend. {@link #complete} never blocks the caller and never throws: failures complete the
 * future exceptionally with an {@link LlmException}. Results are delivered on a provider thread; the Fabric layer
 * hops back to the server thread before touching game state.
 */
public interface LlmProvider extends AutoCloseable {
	String id();

	String model();

	/** False while the provider is cooling down after failures or is disabled; callers should fall back at once. */
	boolean healthy();

	CompletableFuture<LlmResponse> complete(LlmRequest request);

	LlmStats stats();

	@Override
	default void close() {
	}
}
