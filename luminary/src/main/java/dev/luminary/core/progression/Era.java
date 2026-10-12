package dev.luminary.core.progression;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Set;

/**
 * A civilization era. Eras are ordered; the civilization enters the next era when that era's prerequisites hold.
 * The first era (lowest order) needs no prerequisites and is entered on a fresh world.
 */
public record Era(LuminaryId id, int order, String titleKey, Set<Prerequisite> prerequisites) {
	public Era {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(titleKey, "titleKey");
		prerequisites = Set.copyOf(prerequisites);
	}
}
