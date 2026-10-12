package dev.luminary.fabric.definitions;

import dev.luminary.core.catalog.Templates;
import dev.luminary.core.npc.NpcDefinitions;
import dev.luminary.core.progression.ProgressionDefinitions;
import dev.luminary.core.story.StoryDefinitions;

/** Everything loaded from data packs, replaced atomically on reload. */
public record Definitions(Templates discoveries, ProgressionDefinitions progression, StoryDefinitions story, NpcDefinitions npcs) {
	public static Definitions empty() {
		return new Definitions(new Templates(), new ProgressionDefinitions(), new StoryDefinitions(), new NpcDefinitions());
	}
}
