package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcRosterTest {
	static final LuminaryId AI = LuminaryId.of("npc/ai");
	static final UUID BODY1 = UUID.nameUUIDFromBytes("b1".getBytes()), BODY2 = UUID.nameUUIDFromBytes("b2".getBytes());
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

	@Test
	void oneBodyPerNpcUntilMarkedMissing() {
		NpcRoster r = new NpcRoster();
		assertEquals(NpcRoster.RegisterResult.REGISTERED, r.register(AI, BODY1, "minecraft:overworld", 5L, NOW));
		assertEquals(NpcRoster.RegisterResult.ALREADY_THIS_ENTITY, r.register(AI, BODY1, "minecraft:overworld", 6L, NOW));
		assertEquals(NpcRoster.RegisterResult.OCCUPIED, r.register(AI, BODY2, "minecraft:overworld", 7L, NOW));
		assertTrue(r.isBody(AI, BODY1));
		assertFalse(r.isBody(AI, BODY2));
		assertEquals(6L, r.get(AI).orElseThrow().lastPosPacked(), "re-registering the same body updates position");
		r.markMissing(AI);
		assertTrue(r.get(AI).orElseThrow().entityUuid().isEmpty());
		assertEquals(NpcRoster.RegisterResult.REGISTERED, r.register(AI, BODY2, "minecraft:the_nether", 8L, NOW));
		assertEquals("minecraft:the_nether", r.get(AI).orElseThrow().dimension().orElseThrow());
	}

	@Test
	void memorySurvivesMissingBodyAndCountsInteractions() {
		NpcRoster r = new NpcRoster();
		r.register(AI, BODY1, "minecraft:overworld", 0L, NOW);
		NpcRoster.PlayerMemory m1 = r.remember(AI, ALICE, "Alice", NOW, "line.a");
		NpcRoster.PlayerMemory m2 = r.remember(AI, ALICE, "Alice2", NOW.plusSeconds(10), "line.b");
		assertEquals(1, m1.interactions());
		assertEquals(2, m2.interactions());
		assertEquals(NOW, m2.firstMet());
		assertEquals("Alice2", m2.nameAtLastMeeting());
		assertEquals("line.b", m2.lastLineKey());
		r.markMissing(AI);
		assertEquals(2, r.get(AI).orElseThrow().player(ALICE).orElseThrow().interactions());
	}

	@Test
	void codecRoundTrip() {
		NpcRoster r = new NpcRoster();
		r.register(AI, BODY1, "minecraft:overworld", 12345L, NOW);
		r.remember(AI, ALICE, "Alice", NOW, "line.a");
		r.remember(LuminaryId.of("npc/other"), ALICE, "Alice", NOW, null);   // memory without a body ever
		NpcStateCodec codec = NpcStateCodec.production();
		String json = codec.encode(r);
		NpcRoster copy = codec.decode(json);
		assertEquals(json, codec.encode(copy));
		assertEquals(BODY1, copy.get(AI).orElseThrow().entityUuid().orElseThrow());
		assertEquals(12345L, copy.get(AI).orElseThrow().lastPosPacked());
		assertEquals("line.a", copy.get(AI).orElseThrow().player(ALICE).orElseThrow().lastLineKey());
		assertTrue(copy.get(LuminaryId.of("npc/other")).orElseThrow().entityUuid().isEmpty());
		assertEquals(0, codec.decode("{\"schemaVersion\":1}").all().size());
	}
}
