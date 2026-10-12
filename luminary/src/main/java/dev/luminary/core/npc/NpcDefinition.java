package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.List;
import java.util.Objects;

/**
 * A named, unique NPC. One definition = at most one living entity per world (enforced by {@link NpcRoster}).
 * {@code dialogue} names the {@link DialogueScript} the NPC speaks from; {@code persona} is authored character text
 * (identity, voice, boundaries) given to a model-backed provider. It is data, never generated.
 */
public record NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona) {
	public NpcDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(nameKey, "nameKey");
		Objects.requireNonNull(dialogue, "dialogue");
		persona = List.copyOf(persona);
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue) {
		this(id, nameKey, dialogue, List.of());
	}
}
