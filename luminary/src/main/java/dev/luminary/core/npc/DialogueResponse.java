package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Optional;

/** A chosen line. {@code clue} is set when speaking this line reveals an authored clue (the caller performs the reveal). */
public record DialogueResponse(String textKey, Optional<LuminaryId> clue, String providerId) {
	public DialogueResponse {
		Objects.requireNonNull(textKey);
		Objects.requireNonNull(clue);
		Objects.requireNonNull(providerId);
	}
}
