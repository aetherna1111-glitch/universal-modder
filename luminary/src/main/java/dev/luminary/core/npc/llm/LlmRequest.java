package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded completion request. {@code jsonSchema} asks the model for a JSON object of that shape (structured
 * output); callers still validate the result, a schema is a hint to the model, never a guarantee.
 */
public record LlmRequest(List<LlmMessage> messages, Optional<JsonObject> jsonSchema, int maxOutputTokens, double temperature) {
	public LlmRequest {
		messages = List.copyOf(messages);
		Objects.requireNonNull(jsonSchema);
		if (messages.isEmpty()) {
			throw new IllegalArgumentException("a request needs at least one message");
		}
		if (maxOutputTokens < 1 || maxOutputTokens > 4096) {
			throw new IllegalArgumentException("maxOutputTokens out of range: " + maxOutputTokens);
		}
		if (temperature < 0 || temperature > 2) {
			throw new IllegalArgumentException("temperature out of range: " + temperature);
		}
	}

	public static LlmRequest text(List<LlmMessage> messages, int maxOutputTokens, double temperature) {
		return new LlmRequest(messages, Optional.empty(), maxOutputTokens, temperature);
	}

	public static LlmRequest structured(List<LlmMessage> messages, JsonObject schema, int maxOutputTokens) {
		return new LlmRequest(messages, Optional.of(schema), maxOutputTokens, 0.2);
	}

	/** Rough prompt size for budgets and the journal. */
	public int promptChars() {
		return messages.stream().mapToInt(m -> m.content().length()).sum();
	}
}
