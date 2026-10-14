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
}
