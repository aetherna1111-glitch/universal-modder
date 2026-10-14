package dev.luminary.core.npc.trade;

import dev.luminary.core.ids.LuminaryId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** One player's standing with the NPC's economy: points, quests done, quests underway with their kill progress, purchases. Persisted. */
public final class PlayerLedger {
	/** Progress of one accepted quest: kills so far by entity id. */
	public static final class Progress {
		private final Map<String, Integer> kills = new TreeMap<>();

		public Map<String, Integer> kills() {
			return Collections.unmodifiableMap(kills);
		}

		public int count(String entityId) {
			return kills.getOrDefault(entityId, 0);
		}

		void add(String entityId) {
			kills.merge(entityId, 1, Integer::sum);
		}

		void reset() {
			kills.clear();
		}

		public void restore(String entityId, int count) {
			kills.put(entityId, count);
		}

		public boolean satisfies(Quest q) {
			return q.kills().entrySet().stream().allMatch(e -> count(e.getKey()) >= e.getValue());
		}

		public int total() {
			return kills.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	private int points;
	private int purchases;
	private final Set<LuminaryId> completed = new LinkedHashSet<>();
	private final Map<LuminaryId, Progress> active = new LinkedHashMap<>();
	private boolean specialClaimed;

	public int points() {
		return points;
	}

	public int purchases() {
		return purchases;
	}

	public Set<LuminaryId> completed() {
		return Collections.unmodifiableSet(completed);
	}

	public Map<LuminaryId, Progress> active() {
		return Collections.unmodifiableMap(active);
	}

	public Optional<Progress> progress(LuminaryId quest) {
		return Optional.ofNullable(active.get(quest));
	}

	public boolean specialClaimed() {
		return specialClaimed;
	}

	public boolean isCompleted(LuminaryId quest) {
		return completed.contains(quest);
	}

	public boolean isActive(LuminaryId quest) {
		return active.containsKey(quest);
	}

	/** The special quest is offered after enough quests with nothing bought, once. */
	public boolean specialAvailable() {
		return !specialClaimed && purchases == 0 && completed.size() >= TradeRules.SPECIAL_AFTER_QUESTS;
	}

	// ---- mutations, through TradeService

	void accept(LuminaryId quest) {
		active.putIfAbsent(quest, new Progress());
	}

	void abandon(LuminaryId quest) {
		active.remove(quest);
	}

	void complete(Quest quest) {
		active.remove(quest.id());
		completed.add(quest.id());
		points += quest.rewardPoints();
		if (quest.special()) {
			specialClaimed = true;
		}
	}

	boolean spend(int price) {
		if (price > points) {
			return false;
		}
		points -= price;
		purchases++;
		return true;
	}

	void grant(int amount) {
		points = Math.max(0, points + amount);
	}

	// ---- codec

	public void restore(int points, int purchases, boolean specialClaimed) {
		this.points = points;
		this.purchases = purchases;
		this.specialClaimed = specialClaimed;
	}

	public void restoreCompleted(LuminaryId quest) {
		completed.add(quest);
	}

	public Progress restoreActive(LuminaryId quest) {
		return active.computeIfAbsent(quest, k -> new Progress());
	}
}
