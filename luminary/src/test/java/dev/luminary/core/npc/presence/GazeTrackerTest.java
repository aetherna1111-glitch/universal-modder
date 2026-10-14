package dev.luminary.core.npc.presence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GazeTrackerTest {
	private final GazeTracker.Rules rules = new GazeTracker.Rules(Duration.ofSeconds(45), Duration.ofSeconds(10), Duration.ofMillis(600));
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");
	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();

	@Test
	void reactsOnceTheGazeLastedAndThenCoolsDownPerPlayer() {
		GazeTracker g = new GazeTracker(rules);
		assertTrue(g.onGazing(a, t0).isEmpty(), "a glance is not a gaze");
		assertTrue(g.onGazing(a, t0.plusMillis(500)).isEmpty());
		GazeTracker.Reaction r = g.onGazing(a, t0.plusMillis(600)).orElseThrow();
		assertEquals(0, r.sequence());
		assertTrue(g.onGazing(a, t0.plusSeconds(30)).isEmpty(), "inside the per-player cooldown");
		assertEquals(1, g.onGazing(a, t0.plusSeconds(46)).orElseThrow().sequence());
	}

	@Test
	void globalCooldownSpacesReactionsBetweenPlayers() {
		GazeTracker g = new GazeTracker(rules);
		g.onGazing(a, t0);
		g.onGazing(b, t0);
		assertTrue(g.onGazing(a, t0.plusSeconds(1)).isPresent());
		assertTrue(g.onGazing(b, t0.plusSeconds(2)).isEmpty(), "another player right after: global cooldown");
		assertTrue(g.onGazing(b, t0.plusSeconds(12)).isPresent());
	}

	@Test
	void lookingAwayRestartsTheGazeAndTalkingCountsAsAcknowledged() {
		GazeTracker g = new GazeTracker(rules);
		g.onGazing(a, t0);
		g.onLookedAway(a);
		assertTrue(g.onGazing(a, t0.plusMillis(700)).isEmpty(), "gaze restarted at 700 ms");
		assertTrue(g.onGazing(a, t0.plusMillis(1300)).isPresent());
		g.onSpoke(b, t0.plusSeconds(20));
		g.onGazing(b, t0.plusSeconds(20));
		assertTrue(g.onGazing(b, t0.plusSeconds(21)).isEmpty(), "just spoke: no gaze line");
	}

	@Test
	void rejectsNegativeCooldowns() {
		assertThrows(IllegalArgumentException.class, () -> new GazeTracker.Rules(Duration.ofSeconds(-1), Duration.ZERO, Duration.ZERO));
	}
}
