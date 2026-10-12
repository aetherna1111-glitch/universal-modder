package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a provider may know about one interaction. Built on the server thread from authoritative state; a
 * provider never receives world objects, only this immutable snapshot.
 */
public record DialogueRequest(LuminaryId npc, DialogueScript script, UUID player, String playerName,
							  Optional<NpcRoster.PlayerMemory> memory) {
	public DialogueRequest {
		Objects.requireNonNull(npc);
		Objects.requireNonNull(script);
		Objects.requireNonNull(player);
		Objects.requireNonNull(playerName);
		Objects.requireNonNull(memory);
	}
}
