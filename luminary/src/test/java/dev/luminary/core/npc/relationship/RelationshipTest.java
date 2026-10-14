package dev.luminary.core.npc.relationship;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipTest {
	private final RelationshipRules rules = RelationshipRules.DEFAULTS;
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");
	private final UUID p = UUID.randomUUID();

	@Test
	void attacksRaiseThreatAndLowerKarmaWhileDuelHitsDoNot() {
		RelationshipLedger l = new RelationshipLedger();
		Relationship r = l.apply(p, RelationshipEvent.ATTACKED, t0, rules);
		assertEquals(-8, r.karma());
		assertEquals(30, r.threat());
		assertEquals(Relationship.Standing.WARY, r.standing(rules));
		Relationship d = new RelationshipLedger().apply(p, RelationshipEvent.DUEL_HIT, t0, rules);
		assertEquals(0, d.karma(), "a consented duel costs no karma");
		assertEquals(25, d.threat());
	}

	@Test
	void threatDecaysByHalfLifeAndKarmaRecoversSlowly() {
		Relationship r = Relationship.NEUTRAL.apply(RelationshipEvent.ATTACKED_WHEN_WARNED, t0, rules);
		assertEquals(45, r.threat());
		Relationship later = r.decayed(t0.plus(Duration.ofMinutes(10)), rules);
		assertEquals(22.5, later.threat(), 0.01);
		assertEquals(-15, later.karma(), 0.05, "ten minutes barely move karma");
		Relationship week = r.decayed(t0.plus(Duration.ofDays(7)), rules);
		assertEquals(-1, week.karma(), 0.01, "2 per day towards zero");
		assertEquals(0, week.threat());
		assertEquals(Relationship.Standing.NEUTRAL, week.standing(rules));
	}

	@Test
	void apologyForgivesMostOfTheThreatAndGiftsBuildFriendship() {
		RelationshipLedger l = new RelationshipLedger();
		l.apply(p, RelationshipEvent.ATTACKED, t0, rules);
		l.apply(p, RelationshipEvent.ATTACKED, t0, rules);
		assertEquals(Relationship.Standing.HOSTILE, l.standing(p, t0, rules));
		Relationship after = l.apply(p, RelationshipEvent.APOLOGIZED, t0, rules);
		assertEquals(20, after.threat(), 0.01);
		assertEquals(0, after.attacks());
		RelationshipLedger f = new RelationshipLedger();
		for (int i = 0; i < 5; i++) f.apply(p, RelationshipEvent.GIFT, t0.plusSeconds(i), rules);
		assertEquals(Relationship.Standing.FRIEND, f.standing(p, t0.plusSeconds(4), rules));
	}

	@Test
	void theftIsItsOwnHeavyEventAndValuesAreClamped() {
		RelationshipLedger l = new RelationshipLedger();
		Relationship r = l.apply(p, RelationshipEvent.STOLE_WEAPON, t0, rules);
		assertEquals(-30, r.karma());
		assertEquals(0, r.trust(), "trust cannot go below zero");
		for (int i = 0; i < 10; i++) r = r.apply(RelationshipEvent.STOLE_WEAPON, t0.plusSeconds(i + 1), rules);
		assertEquals(-100, r.karma());
		assertEquals(100, r.threat());
	}
}
