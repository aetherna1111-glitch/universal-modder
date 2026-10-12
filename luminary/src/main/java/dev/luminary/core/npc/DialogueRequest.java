package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.memory.MemoryEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a provider may know about one interaction. Built on the server thread from authoritative state; a
 * provider never receives world objects, only this immutable snapshot. {@code playerUtterance} is present when the
 * player spoke in chat near the NPC (absent for a plain right-click); {@code memories} are the retrieved, budgeted
 * entries relevant to this player and utterance.
 */
public record DialogueRequest(LuminaryId npc, DialogueScript script, UUID player, String playerName,
							  Optional<NpcRoster.PlayerMemory> memory, Optional<String> playerUtterance, List<MemoryEntry> memories) {
	public DialogueRequest {
		Objects.requireNonNull(npc);
		Objects.requireNonNull(script);
		Objects.requireNonNull(player);
		Objects.requireNonNull(playerName);
		Objects.requireNonNull(memory);
		Objects.requireNonNull(playerUtterance);
		memories = List.copyOf(memories);
	}
}
