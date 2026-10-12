package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.story.StoryCondition;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One authored line. It is eligible when every {@code conditions} entry holds; a line that carries a {@code clue} is
 * additionally gated by {@code StoryController.mayReveal(clue)} and, when spoken, goes through the normal clue-reveal
 * flow. Higher {@code priority} wins; equal priorities rotate per player so repeated talks vary deterministically.
 */
public record DialogueLine(String textKey, int priority, Set<StoryCondition> conditions, Optional<LuminaryId> clue) {
	public DialogueLine {
		Objects.requireNonNull(textKey, "textKey");
		conditions = Set.copyOf(conditions);
		Objects.requireNonNull(clue, "clue");
	}

	public static DialogueLine plain(String textKey, int priority) {
		return new DialogueLine(textKey, priority, Set.of(), Optional.empty());
	}
}
