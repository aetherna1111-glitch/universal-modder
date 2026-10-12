package dev.luminary.core.npc.llm;

import java.time.Clock;

/** The one place that knows the provider ids. */
public final class LlmProviders {
	private LlmProviders() {
	}

	public static LlmProvider create(LlmConfig config, Clock clock) {
		switch (config.provider()) {
			case LlmConfig.PROVIDER_NONE:
				return new NoLlmProvider();
			case LlmConfig.PROVIDER_OLLAMA:
				return new OllamaProvider(config, clock);
			case LlmConfig.PROVIDER_OPENAI:
				return new OpenAiCompatibleProvider(config, clock);
			default:
				throw new IllegalArgumentException("unknown llm.provider '" + config.provider() + "' (known: none, ollama, openai)");
		}
	}
}
