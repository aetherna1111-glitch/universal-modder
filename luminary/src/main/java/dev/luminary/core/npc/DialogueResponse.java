package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Optional;

/**
 * A chosen line. Scripted lines are lang keys ({@code textKey}); a model-backed provider returns literal text in
 * {@code literalText} (then {@code textKey} is a generic key used only for logging). {@code clue} is set when speaking
 * this line reveals an authored clue (the caller performs the reveal). {@code inferredNote} is an optional one-sentence
 * observation about the player that the caller may store as a low-trust assumption.
 */
public record DialogueResponse(String textKey, Optional<String> literalText, Optional<LuminaryId> clue, String providerId, Optional<String> inferredNote) {
	public static final String LITERAL_KEY = "npc.luminary.model_line";

	public DialogueResponse {
		Objects.requireNonNull(textKey);
		Objects.requireNonNull(literalText);
		Objects.requireNonNull(clue);
		Objects.requireNonNull(providerId);
		Objects.requireNonNull(inferredNote);
	}

	public DialogueResponse(String textKey, Optional<LuminaryId> clue, String providerId) {
		this(textKey, Optional.empty(), clue, providerId, Optional.empty());
	}

	public static DialogueResponse literal(String text, Optional<LuminaryId> clue, String providerId, Optional<String> inferredNote) {
		return new DialogueResponse(LITERAL_KEY, Optional.of(text), clue, providerId, inferredNote);
	}
}
