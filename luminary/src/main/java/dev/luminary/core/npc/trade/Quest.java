package dev.luminary.core.npc.trade;

import dev.luminary.core.ids.LuminaryId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A task the NPC hands out for its points. {@code kills} maps entity type ids to counts; progress counts kills by
 * the player after acceptance. A {@code special} quest is offered only to a player who completed
 * {@value TradeRules#SPECIAL_AFTER_QUESTS} quests and bought nothing; its reward is the NPC's weapon, not points.
 * {@code resetOnDeath}: dying after acceptance clears the progress. Authored data, loaded from
 * {@code data/<ns>/luminary/quests/*.json}. {@code order} sorts the list the NPC shows (story order).
 */
public record Quest(LuminaryId id, LuminaryId npc, String titleKey, String descriptionKey, Map<String, Integer> kills, int rewardPoints,
					boolean special, boolean resetOnDeath, List<String> rewardItems, int order) {
	public Quest(LuminaryId id, LuminaryId npc, String titleKey, String descriptionKey, Map<String, Integer> kills, int rewardPoints,
				 boolean special, boolean resetOnDeath, List<String> rewardItems) {
		this(id, npc, titleKey, descriptionKey, kills, rewardPoints, special, resetOnDeath, rewardItems, 0);
	}

	public Quest {
		Objects.requireNonNull(id);
		Objects.requireNonNull(npc);
		Objects.requireNonNull(titleKey);
		Objects.requireNonNull(descriptionKey);
		kills = Map.copyOf(kills);
		rewardItems = List.copyOf(rewardItems);
		if (kills.isEmpty()) {
			throw new IllegalArgumentException("quest " + id + " has nothing to do");
		}
		if (rewardPoints < 0) {
			throw new IllegalArgumentException("quest " + id + " has a negative reward");
		}
	}

	public int totalRequired() {
		return kills.values().stream().mapToInt(Integer::intValue).sum();
	}
}
