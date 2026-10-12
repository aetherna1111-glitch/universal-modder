package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Optional;

/** A completed request. {@code structured} is present when the text parsed as a JSON object. */
public record LlmResponse(String text, Optional<JsonObject> structured, long latencyMillis, String providerId, String model) {
	public LlmResponse {
		Objects.requireNonNull(text);
		Objects.requireNonNull(structured);
		Objects.requireNonNull(providerId);
		Objects.requireNonNull(model);
	}
}
