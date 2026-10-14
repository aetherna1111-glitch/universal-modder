package dev.luminary.core.npc.relationship;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The NPC's standing towards one player. Immutable value; {@link RelationshipLedger} applies events. Threat and
 * karma are stored as of {@code updatedAt} and decayed lazily when read.
 */
public record Relationship(double karma, double trust, double threat, Instant updatedAt, Optional<RelationshipEvent> lastEvent, int attacks) {
	public static final Relationship NEUTRAL = new Relationship(0, 10, 0, Instant.EPOCH, Optional.empty(), 0);

	public Relationship {
		Objects.requireNonNull(updatedAt);
		Objects.requireNonNull(lastEvent);
	}

	/** Threat halves every half-life; karma drifts towards zero; trust is kept. */
	public Relationship decayed(Instant now, RelationshipRules rules) {
		if (!now.isAfter(updatedAt)) {
			return this;
		}
		Duration dt = Duration.between(updatedAt, now);
		double halfLives = dt.toMillis() / (double) rules.threatHalfLife().toMillis();
		double t = threat * Math.pow(0.5, halfLives);
		double days = dt.toMillis() / 86_400_000.0;
		double recover = rules.karmaRecoveryPerDay() * days;
		double k = karma > 0 ? Math.max(0, karma - recover) : Math.min(0, karma + recover);
		return new Relationship(k, trust, t < 0.5 ? 0 : t, now, lastEvent, attacks);
	}

	public Relationship apply(RelationshipEvent event, Instant now, RelationshipRules rules) {
		Relationship d = decayed(now, rules);
		RelationshipRules.Delta delta = rules.delta(event);
		int hits = switch (event) {
			case ATTACKED, ATTACKED_WHEN_WARNED, DUEL_HIT -> d.attacks + 1;
			case APOLOGIZED -> 0;
			default -> d.attacks;
		};
		return new Relationship(clamp(d.karma + delta.karma(), -100, 100), clamp(d.trust + delta.trust(), 0, 100),
				clamp(d.threat + delta.threat(), 0, 100), now, Optional.of(event), hits);
	}

	public Standing standing(RelationshipRules rules) {
		if (threat >= rules.hostileThreat()) {
			return Standing.HOSTILE;
		}
		if (threat >= rules.waryThreat() || karma <= -40) {
			return Standing.WARY;
		}
		if (karma >= rules.friendKarma() && trust >= rules.friendTrust()) {
			return Standing.FRIEND;
		}
		return Standing.NEUTRAL;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Coarse standing used for behaviour and prompts. */
	public enum Standing { FRIEND, NEUTRAL, WARY, HOSTILE }
}
