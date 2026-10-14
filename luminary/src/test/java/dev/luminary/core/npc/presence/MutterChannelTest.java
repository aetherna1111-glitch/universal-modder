package dev.luminary.core.npc.presence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MutterChannelTest {
	private final MutterChannel.Rules rules = new MutterChannel.Rules(5.0, Duration.ofSeconds(90), Duration.ZERO, true);
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");

	@Test
	void silentWithoutListenersAndRateLimitedWithThem() {
		MutterChannel m = new MutterChannel(rules);
		assertTrue(m.tick(Activity.READING, false, t0).isEmpty(), "nobody near");
		Optional<MutterChannel.Mutter> first = m.tick(Activity.READING, true, t0.plusSeconds(1));
		assertEquals("mutter.reading", first.orElseThrow().lineKey());
		assertTrue(m.tick(Activity.READING, true, t0.plusSeconds(60)).isEmpty(), "cooldown");
		assertEquals(1, m.tick(Activity.READING, true, t0.plusSeconds(92)).orElseThrow().sequence());
	}

	@Test
	void holdsWhileTalkingAndCanBeDisabled() {
		MutterChannel m = new MutterChannel(rules);
		m.holdFor(Duration.ofSeconds(30), t0);
		assertTrue(m.tick(Activity.IDLE, true, t0.plusSeconds(10)).isEmpty());
		assertTrue(m.tick(Activity.IDLE, true, t0.plusSeconds(31)).isPresent());
		MutterChannel off = new MutterChannel(new MutterChannel.Rules(5.0, Duration.ofSeconds(1), Duration.ZERO, false));
		assertTrue(off.tick(Activity.IDLE, true, t0).isEmpty());
	}

	@Test
	void jitterIsDeterministic() {
		MutterChannel a = new MutterChannel(new MutterChannel.Rules(5.0, Duration.ofSeconds(90), Duration.ofSeconds(45), true));
		MutterChannel b = new MutterChannel(new MutterChannel.Rules(5.0, Duration.ofSeconds(90), Duration.ofSeconds(45), true));
		a.tick(Activity.IDLE, true, t0);
		b.tick(Activity.IDLE, true, t0);
		assertEquals(a.nextAllowed(), b.nextAllowed());
		assertTrue(a.nextAllowed().isAfter(t0.plusSeconds(89)) && a.nextAllowed().isBefore(t0.plusSeconds(136)));
	}
}
