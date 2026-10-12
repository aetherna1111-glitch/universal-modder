package dev.luminary.core.story;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.Prerequisite;

/**
 * A condition on the civilization's state used by phases (activation) and clues (reveal). Progression prerequisites
 * are reused; the rest are story-specific. There is no condition on wall-clock time.
 */
public sealed interface StoryCondition {
	/** Any progression prerequisite (era, verified discovery, unlocked technology, signal). */
	record Progression(Prerequisite prerequisite) implements StoryCondition {}

	/** The civilization chose this strategic approach (containment / investigation / exploitation ...). */
	record BranchChosen(LuminaryId branch) implements StoryCondition {}

	/** Another clue was already revealed (clues that only make sense in retrospect). */
	record ClueRevealed(LuminaryId clue) implements StoryCondition {}

	/** The story is in this phase or a later one. */
	record PhaseAtLeast(LuminaryId phase) implements StoryCondition {}
}
