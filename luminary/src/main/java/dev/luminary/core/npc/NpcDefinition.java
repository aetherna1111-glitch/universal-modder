package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.List;
import java.util.Objects;

/**
 * A named, unique NPC. One definition = at most one living entity per world (enforced by {@link NpcRoster}).
 * {@code dialogue} names the {@link DialogueScript} the NPC speaks from; {@code persona} is authored character text
 * (identity, voice, boundaries) given to a model-backed provider. It is data, never generated.
 */
public record NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona, List<String> aliases) {
	public NpcDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(nameKey, "nameKey");
		Objects.requireNonNull(dialogue, "dialogue");
		persona = List.copyOf(persona);
		List<String> cleaned = new java.util.ArrayList<>();
		for (String a : aliases) {
			String t = a == null ? "" : a.strip().toLowerCase(java.util.Locale.ROOT);
			if (!t.isEmpty() && !cleaned.contains(t)) cleaned.add(t);
		}
		if (cleaned.isEmpty()) {
			String path = id.path();
			cleaned.add(path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT));
		}
		aliases = List.copyOf(cleaned);
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona) {
		this(id, nameKey, dialogue, persona, List.of());
	}

	/** Whether a chat line addresses this NPC: it contains one of the aliases (case-insensitive; "Люмин," and "Люмину" both match "люмин"). */
	public boolean isAddressed(String text) {
		if (text == null) {
			return false;
		}
		String t = text.toLowerCase(java.util.Locale.ROOT);
		for (String a : aliases) {
			if (t.contains(a)) {
				return true;
			}
		}
		return false;
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue) {
		this(id, nameKey, dialogue, List.of());
	}
}
