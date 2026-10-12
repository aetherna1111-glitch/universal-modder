package dev.luminary.core.story;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;

/** A strategic approach the civilization can commit to (containment, investigation, exploitation, ...). */
public record StoryBranch(LuminaryId id, String titleKey) {
	public StoryBranch {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(titleKey, "titleKey");
	}
}
