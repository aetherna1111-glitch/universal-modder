package dev.luminary.core.npc.intent;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.memory.MemoryEntry;

import java.util.List;
import java.util.UUID;

/** One request from a player to an NPC, with everything a planner may consider. */
public record IntentRequest(LuminaryId npc, UUID player, String playerName, String utterance, List<String> persona,
							List<MemoryEntry> memories, IntentCandidates candidates, boolean busy) {}
