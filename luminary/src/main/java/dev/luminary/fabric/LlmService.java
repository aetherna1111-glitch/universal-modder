package dev.luminary.fabric;

import dev.luminary.core.npc.llm.LlmConfig;
import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmJournal;
import dev.luminary.core.npc.llm.LlmProvider;
import dev.luminary.core.npc.llm.LlmProviders;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.core.npc.llm.LlmResponse;
import dev.luminary.core.npc.llm.NoLlmProvider;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Owns the configured {@link LlmProvider} and the journal, and hops results back to the server thread.
 * The game never blocks on a model: {@link #ask} returns at once and {@code onServerThread} runs later via
 * {@code MinecraftServer.execute}, or never runs if the server stopped meanwhile.
 */
public final class LlmService {
	private final MinecraftServer server;
	private final LlmProvider provider;
	private final LlmJournal journal;
	private final LlmConfig config;

	LlmService(MinecraftServer server, LlmConfig config, boolean logContent) {
		this.server = server;
		this.config = config;
		LlmProvider p;
		try {
			p = LlmProviders.create(config, Clock.systemUTC());
		} catch (IllegalArgumentException e) {
			Luminary.LOGGER.error("[luminary] llm disabled: {}", e.getMessage());
			p = new NoLlmProvider();
		}
		this.provider = p;
		Path journalFile = server.getRunDirectory().toPath().resolve("logs").resolve("luminary").resolve("llm.jsonl");
		this.journal = new LlmJournal(journalFile, 4L << 20, 3, logContent, Clock.systemUTC(), msg -> Luminary.LOGGER.warn("[luminary] {}", msg));
		Luminary.LOGGER.info("[luminary] llm provider '{}' model '{}' endpoint {} (api key {})", provider.id(), provider.model(),
				config.endpoint(), config.apiKey().isPresent() ? "set" : "not set");
	}

	public LlmProvider provider() {
		return provider;
	}

	public LlmConfig config() {
		return config;
	}

	/**
	 * Sends a request and delivers the outcome on the server thread. Exactly one of the two arguments of
	 * {@code onServerThread} is non-null. {@code purpose} is a short label for the journal (dialogue, intent, ping).
	 */
	public void ask(LlmRequest request, String purpose, BiConsumer<LlmResponse, LlmException> onServerThread) {
		CompletableFuture<LlmResponse> future = provider.complete(request);
		future.whenComplete((response, error) -> {
			LlmException failure = error == null ? null : asLlmException(error);
			journal.record(provider.id(), provider.model(), request, response, failure, purpose);
			server.execute(() -> onServerThread.accept(response, failure));
		});
	}

	private static LlmException asLlmException(Throwable t) {
		Throwable c = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
		return c instanceof LlmException e ? e : new LlmException(LlmException.Kind.TRANSPORT, String.valueOf(c.getMessage()), c);
	}

	void close() {
		provider.close();
	}
}
