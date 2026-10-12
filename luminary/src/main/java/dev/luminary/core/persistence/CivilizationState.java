package dev.luminary.core.persistence;

import dev.luminary.core.catalog.CatalogState;
import dev.luminary.core.progression.ProgressionState;
import dev.luminary.core.story.StoryState;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything Luminary persists per world, as one aggregate. Progression, Story and City state will be added as
 * fields with their own schema bumps; {@link #executedEffectIds} prevents duplicate rewards and repeated events.
 */
public final class CivilizationState {
	/** Bump when the JSON shape changes; add a step to {@link SchemaMigrations}. */
	public static final int CURRENT_SCHEMA_VERSION = 3;

	private final CatalogState catalog;
	private final ProgressionState progression;
	private final StoryState story;
	private final Set<String> executedEffectIds;

	public CivilizationState() {
		this(new CatalogState(), new ProgressionState(), new StoryState(), new TreeSet<>());
	}

	public CivilizationState(CatalogState catalog, ProgressionState progression, StoryState story, Set<String> executedEffectIds) {
		this.catalog = Objects.requireNonNull(catalog);
		this.progression = Objects.requireNonNull(progression);
		this.story = Objects.requireNonNull(story);
		this.executedEffectIds = new TreeSet<>(executedEffectIds);
	}

	public StoryState story() {
		return story;
	}

	public CatalogState catalog() {
		return catalog;
	}

	public ProgressionState progression() {
		return progression;
	}

	public Set<String> executedEffectIds() {
		return executedEffectIds;
	}

	/** Returns true the first time {@code effectId} is claimed; false afterwards (the effect must not run again). */
	public boolean claimEffect(String effectId) {
		return executedEffectIds.add(effectId);
	}
}
