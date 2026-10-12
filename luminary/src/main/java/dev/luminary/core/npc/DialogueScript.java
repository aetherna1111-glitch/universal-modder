package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.List;
import java.util.Objects;

/** An ordered set of lines for one NPC (or shared between NPCs). Order breaks priority ties deterministically. */
public record DialogueScript(LuminaryId id, List<DialogueLine> lines) {
	public DialogueScript {
		Objects.requireNonNull(id, "id");
		lines = List.copyOf(lines);
		if (lines.isEmpty()) {
			throw new IllegalArgumentException("dialogue " + id + " has no lines");
		}
	}
}
