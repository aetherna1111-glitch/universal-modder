package dev.luminary.core.story;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Set;

/**
 * A narrative phase. Phases are ordered. {@code autoAdvance} is false by default: the story moves on only by an
 * administrator's decision unless the data pack explicitly opts a phase into automatic activation when its
 * {@code activationConditions} hold. The first phase has no conditions and is entered on a fresh world.
 */
public record StoryPhase(LuminaryId id, int order, String titleKey, Set<StoryCondition> activationConditions, boolean autoAdvance) {
	public StoryPhase {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(titleKey, "titleKey");
		activationConditions = Set.copyOf(activationConditions);
	}
}
