package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;

/**
 * A named, unique NPC. One definition = at most one living entity per world (enforced by {@link NpcRoster}).
 * {@code dialogue} names the {@link DialogueScript} the NPC speaks from.
 */
public record NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue) {
	public NpcDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(nameKey, "nameKey");
		Objects.requireNonNull(dialogue, "dialogue");
	}
}
