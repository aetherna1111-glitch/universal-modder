package dev.luminary.core.npc.relationship;

import org.junit.jupiter.api.Test;

import java.util.stream.DoubleStream;

import static org.junit.jupiter.api.Assertions.*;

class KarmaEffectsTest {
	@Test
	void luckFollowsKarmaInHalfPointsUpToFour() {
		assertEquals(0.0, KarmaEffects.luck(0));
		assertEquals(1.0, KarmaEffects.luck(25));
		assertEquals(-1.0, KarmaEffects.luck(-24));
		assertEquals(0.5, KarmaEffects.luck(12));
		assertEquals(4.0, KarmaEffects.luck(100));
		assertEquals(-4.0, KarmaEffects.luck(-100));
	}

	@Test
	void tradersMarkUpOnlyNegativeKarma() {
		assertEquals(0.0, KarmaEffects.markup(30));
		assertEquals(0.0, KarmaEffects.markup(0));
		assertEquals(0.375, KarmaEffects.markup(-50), 1e-9);
		assertEquals(0.75, KarmaEffects.markup(-100), 1e-9);
		assertEquals(0, KarmaEffects.extraPrice(10, 0));
		assertEquals(1, KarmaEffects.extraPrice(1, 0.1), "any markup costs at least one");
		assertEquals(8, KarmaEffects.extraPrice(10, 0.75));
	}

	@Test
	void worthinessNeedsGoodStandingAndNoDebt() {
		assertTrue(KarmaEffects.worthy(Relationship.Standing.NEUTRAL, 0));
		assertTrue(KarmaEffects.worthy(Relationship.Standing.FRIEND, 40));
		assertFalse(KarmaEffects.worthy(Relationship.Standing.NEUTRAL, -1));
		assertFalse(KarmaEffects.worthy(Relationship.Standing.WARY, 50));
		assertFalse(KarmaEffects.worthy(Relationship.Standing.HOSTILE, 50));
		assertEquals(100, KarmaEffects.total(DoubleStream.of(80, 60)), "summed over NPCs, clamped");
		assertEquals(-100, KarmaEffects.total(DoubleStream.of(-80, -60)));
	}

	@Test
	void theLedgerResetsAndSetsKarmaOutright() {
		RelationshipLedger l = new RelationshipLedger();
		java.util.UUID p = java.util.UUID.randomUUID();
		java.time.Instant t = java.time.Instant.parse("2026-10-14T12:00:00Z");
		assertFalse(l.reset(p), "nothing to reset for a stranger");
		l.apply(p, RelationshipEvent.ATTACKED, t, RelationshipRules.DEFAULTS);
		assertTrue(l.get(p, t, RelationshipRules.DEFAULTS).karma() < 0);
		assertEquals(42.0, l.setKarma(p, 42, t, RelationshipRules.DEFAULTS).karma());
		assertEquals(30.0, l.get(p, t, RelationshipRules.DEFAULTS).threat(), "trust and threat are kept");
		assertEquals(100.0, l.setKarma(p, 500, t, RelationshipRules.DEFAULTS).karma(), "clamped");
		assertTrue(l.reset(p));
		assertEquals(0.0, l.get(p, t, RelationshipRules.DEFAULTS).karma());
		assertEquals(0.0, l.get(p, t, RelationshipRules.DEFAULTS).threat());
	}
}
