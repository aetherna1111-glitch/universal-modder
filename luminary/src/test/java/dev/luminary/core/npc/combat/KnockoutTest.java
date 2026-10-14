package dev.luminary.core.npc.combat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KnockoutTest {
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");

	@Test
	void lastsExactlyTheConfiguredRealTimeInUtc() {
		Knockout k = Knockout.start(t0, Duration.ofMinutes(5), UUID.randomUUID());
		assertEquals(t0.plusSeconds(300), k.deadline());
		assertFalse(k.over(t0.plusSeconds(299)));
		assertTrue(k.over(t0.plusSeconds(300)));
		assertEquals(Duration.ofSeconds(100), k.remaining(t0.plusSeconds(200)));
		assertEquals(Duration.ZERO, k.remaining(t0.plusSeconds(400)));
	}

	@Test
	void helpShortensButNeverIntoThePastAndLootIsCounted() {
		Knockout k = Knockout.start(t0, Duration.ofMinutes(5), null);
		Knockout h = k.helped(t0.plusSeconds(10), Duration.ofSeconds(60));
		assertEquals(t0.plusSeconds(240), h.deadline());
		assertEquals(1, h.timesHelped());
		Knockout late = h.helped(t0.plusSeconds(235), Duration.ofSeconds(600));
		assertEquals(t0.plusSeconds(235), late.deadline(), "clamped to now");
		assertTrue(late.over(t0.plusSeconds(235)));
		assertEquals(2, late.looted().looted().timesLooted());
		assertTrue(k.downedBy().isEmpty());
	}
}
