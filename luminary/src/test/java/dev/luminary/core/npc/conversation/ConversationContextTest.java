package dev.luminary.core.npc.conversation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ConversationContextTest {
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");

	@Test
	void keepsTheLastTurnsOnly() {
		ConversationContext c = new ConversationContext(3, Duration.ofMinutes(10));
		for (int i = 0; i < 5; i++) {
			c.add(i % 2 == 0 ? ConversationContext.Role.PLAYER : ConversationContext.Role.NPC, "t" + i, t0.plusSeconds(i));
		}
		assertEquals(3, c.turns(t0.plusSeconds(5)).size());
		assertEquals("t2", c.turns(t0.plusSeconds(5)).get(0).text());
	}

	@Test
	void expiresAfterIdleAndTruncatesLongTurns() {
		ConversationContext c = new ConversationContext();
		c.add(ConversationContext.Role.PLAYER, "x".repeat(500), t0);
		assertEquals(ConversationContext.MAX_TURN_CHARS, c.turns(t0).get(0).text().length());
		assertEquals(1, c.turns(t0.plus(Duration.ofMinutes(9))).size());
		assertTrue(c.turns(t0.plus(Duration.ofMinutes(11))).isEmpty(), "a new meeting starts clean");
		assertTrue(c.isEmpty());
	}
}
