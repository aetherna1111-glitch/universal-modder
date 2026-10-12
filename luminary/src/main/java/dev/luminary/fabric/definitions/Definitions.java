package dev.luminary.fabric.definitions;

import dev.luminary.core.catalog.Templates;
import dev.luminary.core.progression.ProgressionDefinitions;

/** Everything loaded from data packs, replaced atomically on reload. */
public record Definitions(Templates discoveries, ProgressionDefinitions progression) {
	public static Definitions empty() {
		return new Definitions(new Templates(), new ProgressionDefinitions());
	}
}
