package dev.luminary.core.npc.relationship;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Deltas per event and the forgiveness clock. Karma is the long moral ledger (−100..100), trust is how much the NPC
 * relies on the player's word (0..100), threat is the short-term danger estimate (0..100) that decays by
 * {@code threatHalfLife}; karma drifts back towards zero by {@code karmaRecoveryPerDay} per day (forgiveness is
 * slow but real), trust does not recover by itself.
 */
public record RelationshipRules(Map<RelationshipEvent, Delta> deltas, Duration threatHalfLife, double karmaRecoveryPerDay,
								int hostileThreat, int waryThreat, int friendKarma, int friendTrust) {
	public record Delta(int karma, int trust, int threat) {
	}

	public static final RelationshipRules DEFAULTS = new RelationshipRules(defaultDeltas(), Duration.ofMinutes(10), 2.0, 60, 25, 20, 30);

	public RelationshipRules {
		deltas = Map.copyOf(deltas);
		if (threatHalfLife.isNegative() || threatHalfLife.isZero() || karmaRecoveryPerDay < 0) {
			throw new IllegalArgumentException("invalid relationship rules");
		}
	}

	public Delta delta(RelationshipEvent event) {
		return deltas.getOrDefault(event, new Delta(0, 0, 0));
	}

	static Map<RelationshipEvent, Delta> defaultDeltas() {
		Map<RelationshipEvent, Delta> d = new EnumMap<>(RelationshipEvent.class);
		d.put(RelationshipEvent.CONVERSATION, new Delta(0, 1, -2));
		d.put(RelationshipEvent.REQUESTED_WORK, new Delta(1, 2, -1));
		d.put(RelationshipEvent.GIFT, new Delta(5, 6, -10));
		d.put(RelationshipEvent.ATTACKED, new Delta(-8, -10, 30));
		d.put(RelationshipEvent.ATTACKED_WHEN_WARNED, new Delta(-15, -15, 45));
		d.put(RelationshipEvent.DUEL_HIT, new Delta(0, 0, 25));
		d.put(RelationshipEvent.APOLOGIZED, new Delta(4, 3, -40));
		d.put(RelationshipEvent.STOLE_WEAPON, new Delta(-30, -40, 20));
		d.put(RelationshipEvent.RETURNED_WEAPON, new Delta(15, 20, -20));
		d.put(RelationshipEvent.HELPED_WHILE_DOWN, new Delta(12, 15, -30));
		d.put(RelationshipEvent.LOOTED_WHILE_DOWN, new Delta(-6, -8, 5));
		return d;
	}
}
