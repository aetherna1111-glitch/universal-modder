package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.conversation.ConversationContext;
import dev.luminary.core.npc.memory.MemoryEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a provider may know about one interaction. Built on the server thread from authoritative state; a
 * provider never receives world objects, only this immutable snapshot. {@code playerUtterance} is present when the
 * player spoke in chat near the NPC (absent for a plain right-click); {@code memories} are the retrieved, budgeted
 * entries relevant to this player and utterance; {@code characterLines} is the authored character sheet,
 * {@code recentTurns} the bounded multi-turn context with this player and {@code standing} the NPC's current
 * attitude towards them (FRIEND/NEUTRAL/WARY/HOSTILE), all derived from authoritative state; {@code situation} is what
 * the NPC can see and knows right now (time, place, what it is doing, its gear, the player's ledger), one fact per line.
 */
public record DialogueRequest(LuminaryId npc, DialogueScript script, UUID player, String playerName,
							  Optional<NpcRoster.PlayerMemory> memory, Optional<String> playerUtterance, List<MemoryEntry> memories,
							  List<String> persona, List<String> characterLines, List<ConversationContext.Turn> recentTurns, Optional<String> standing,
							  List<String> situation) {
	public DialogueRequest(LuminaryId npc, DialogueScript script, UUID player, String playerName, Optional<NpcRoster.PlayerMemory> memory,
						   Optional<String> playerUtterance, List<MemoryEntry> memories, List<String> persona) {
		this(npc, script, player, playerName, memory, playerUtterance, memories, persona, List.of(), List.of(), Optional.empty(), List.of());
	}

	public DialogueRequest(LuminaryId npc, DialogueScript script, UUID player, String playerName, Optional<NpcRoster.PlayerMemory> memory,
						   Optional<String> playerUtterance, List<MemoryEntry> memories, List<String> persona, List<String> characterLines,
						   List<ConversationContext.Turn> recentTurns, Optional<String> standing) {
		this(npc, script, player, playerName, memory, playerUtterance, memories, persona, characterLines, recentTurns, standing, List.of());
	}

	public DialogueRequest {
		Objects.requireNonNull(npc);
		Objects.requireNonNull(script);
		Objects.requireNonNull(player);
		Objects.requireNonNull(playerName);
		Objects.requireNonNull(memory);
		Objects.requireNonNull(playerUtterance);
		memories = List.copyOf(memories);
		persona = List.copyOf(persona);
		characterLines = List.copyOf(characterLines);
		recentTurns = List.copyOf(recentTurns);
		Objects.requireNonNull(standing);
		situation = List.copyOf(situation);
	}
}
