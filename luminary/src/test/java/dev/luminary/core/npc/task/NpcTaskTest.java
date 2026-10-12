package dev.luminary.core.npc.task;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.NpcStateCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcTaskTest {
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
	static final LuminaryId AI = LuminaryId.of("npc/ai");

	NpcTask task() {
		return new NpcTask(UUID.randomUUID(), new NpcAction.GatherWood(1L, 2L, 3), NOW);
	}

	@Test
	void terminalStatesAreFinalAndCountersResetPerPhase() {
		NpcTask t = task();
		assertTrue(t.enter(TaskState.MOVING_TO_TARGET));
		t.tickPhase(); t.tickPhase();
		assertEquals(2, t.phaseTicks());
		assertTrue(t.enter(TaskState.BREAKING));
		assertEquals(0, t.phaseTicks());
		assertFalse(t.enter(TaskState.COMPLETED), "terminal states are reached through complete()/fail() only");
		assertTrue(t.complete());
		assertFalse(t.fail("late"), "a completed task cannot fail afterwards");
		assertFalse(t.enter(TaskState.VALIDATED));
		assertEquals(TaskState.COMPLETED, t.state());
		assertTrue(t.failureReason().isEmpty());
		NpcTask f = task();
		assertTrue(f.fail(NpcTask.FAIL_CONTAINER_FULL));
		assertFalse(f.complete());
		assertEquals(NpcTask.FAIL_CONTAINER_FULL, f.failureReason().orElseThrow());
	}

	@Test
	void ledgerOnlyGrowsAndIgnoresNonPositiveCounts() {
		TaskLedger l = new TaskLedger();
		l.blockBroken(); l.blockBroken();
		l.collected("minecraft:oak_log", 2);
		l.collected("minecraft:oak_log", 0);
		l.collected("minecraft:oak_log", -5);
		l.deposited("minecraft:oak_log", 1);
		assertEquals(2, l.blocksBroken());
		assertEquals(2, l.collectedTotal());
		assertEquals(1, l.depositedTotal());
	}

	@Test
	void rosterRefusesASecondActiveTaskAndReplacesTerminalOnes() {
		NpcRoster r = new NpcRoster();
		NpcTask a = task();
		assertTrue(r.startTask(AI, a));
		assertFalse(r.startTask(AI, task()), "no concurrent tasks");
		a.fail(NpcTask.FAIL_CANCELLED);
		NpcTask b = task();
		assertTrue(r.startTask(AI, b));
		assertEquals(b.id(), r.get(AI).orElseThrow().task().orElseThrow().id());
	}

	@Test
	void tasksAndContainersSurviveTheCodec() {
		NpcRoster r = new NpcRoster();
		r.authorizeContainer(AI, 777L);
		NpcTask t = task();
		t.enter(TaskState.COLLECTING);
		t.setCurrentTarget(55L);
		t.tickPhase();
		t.ledger().blockBroken();
		t.ledger().collected("minecraft:oak_log", 1);
		r.startTask(AI, t);
		NpcStateCodec codec = NpcStateCodec.production();
		String json = codec.encode(r);
		NpcRoster copy = codec.decode(json);
		assertEquals(json, codec.encode(copy));
		NpcTask ct = copy.get(AI).orElseThrow().task().orElseThrow();
		assertEquals(t.id(), ct.id());
		assertEquals(TaskState.COLLECTING, ct.state());
		assertEquals(55L, ct.currentTarget());
		assertEquals(1, ct.phaseTicks());
		assertEquals(1, ct.ledger().blocksBroken());
		assertEquals(1, ct.ledger().collected().get("minecraft:oak_log"));
		assertTrue(copy.get(AI).orElseThrow().authorizedContainers().contains(777L));
		assertTrue(copy.revokeContainer(AI, 777L));
		// a v1 document without containers/task still loads
		assertTrue(codec.decode("{\"schemaVersion\":1,\"npcs\":{\"luminary:npc/x\":{\"pos\":0,\"players\":{}}}}").get(LuminaryId.of("npc/x")).orElseThrow().task().isEmpty());
	}

	@Test
	void everyActionTypeAndTheRequesterSurviveTheCodec() {
		java.util.UUID alice = java.util.UUID.nameUUIDFromBytes("alice".getBytes());
		NpcStateCodec codec = NpcStateCodec.production();
		for (NpcAction action : java.util.List.of(new NpcAction.MoveTo(5L), new NpcAction.Deposit(6L),
				new NpcAction.Withdraw(6L, "minecraft:oak_log", 3), new NpcAction.GatherWood(1L, 6L, 2))) {
			NpcRoster r = new NpcRoster();
			NpcTask t = new NpcTask(java.util.UUID.randomUUID(), action, java.time.Instant.parse("2026-10-12T00:00:00Z"), alice);
			t.ledger().withdrawn("minecraft:oak_log", 2);
			r.startTask(AI, t);
			String json = codec.encode(r);
			NpcTask ct = codec.decode(json).get(AI).orElseThrow().task().orElseThrow();
			assertEquals(action, ct.action());
			assertEquals(java.util.Optional.of(alice), ct.requestedBy());
			assertEquals(2, ct.ledger().withdrawn().get("minecraft:oak_log"));
			assertEquals(json, codec.encode(codec.decode(json)));
		}
	}
}
