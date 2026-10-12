package dev.luminary.core.story;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Set;

/**
 * An authored piece of restricted narrative information. The text lives in lang files under {@code textKey}; the
 * clue may be surfaced (by the NPC, a book, a command) only when {@link StoryController#mayReveal} is true:
 * the story has reached {@code phase} and every {@code conditions} entry holds. Free-form AI output is never a clue.
 */
public record Clue(LuminaryId id, LuminaryId phase, String textKey, Set<StoryCondition> conditions) {
	public Clue {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(textKey, "textKey");
		conditions = Set.copyOf(conditions);
		for (StoryCondition c : conditions) {
			if (c instanceof StoryCondition.ClueRevealed r && r.clue().equals(id)) {
				throw new IllegalArgumentException(id + " requires itself");
			}
		}
	}
}
