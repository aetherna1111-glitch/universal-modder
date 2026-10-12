package dev.luminary.core.npc.intent;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.TaskRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordIntentPlannerTest {
	static final LuminaryId AI = LuminaryId.of("npc/ai");
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
	static final IntentCandidates.Spot LOG_NEAR = new IntentCandidates.Spot(10L, 1, 2, 3, 9.0), LOG_FAR = new IntentCandidates.Spot(11L, 7, 2, 3, 49.0);
	static final IntentCandidates.ContainerInfo CHEST = new IntentCandidates.ContainerInfo(new IntentCandidates.Spot(20L, 4, 2, 4, 16.0),
			Map.of("minecraft:oak_log", 12, "minecraft:cobblestone", 30));
	static final IntentCandidates.Spot PLAYER = new IntentCandidates.Spot(30L, 0, 2, 0, 4.0);
	final KeywordIntentPlanner planner = new KeywordIntentPlanner(TaskRules.DEFAULTS);

	IntentRequest req(String text, IntentCandidates c, boolean busy) {
		return new IntentRequest(AI, ALICE, "Alice", text, List.of(), List.of(), c, busy);
	}

	IntentCandidates full() {
		return new IntentCandidates(List.of(LOG_FAR, LOG_NEAR), List.of(CHEST), Optional.of(PLAYER), Map.of("minecraft:stick", 2));
	}

	Intent plan(String text) {
		return planner.planNow(req(text, full(), false)).orElseThrow();
	}

	@Test
	void gatherPicksTheNearestLogAndContainerAndReadsACount() {
		Intent i = plan("Could you chop 3 logs for me?");
		assertEquals(new NpcAction.GatherWood(10L, 20L, 3), i.action().orElseThrow());
		assertEquals(Optional.of(Intent.REPLY_WILL_DO), i.replyKey());
		assertEquals(new NpcAction.GatherWood(10L, 20L, KeywordIntentPlanner.DEFAULT_LOGS), plan("we need some wood").action().orElseThrow());
		assertEquals(new NpcAction.GatherWood(10L, 20L, 16), plan("get 200 logs").action().orElseThrow(), "count clamped to the rules");
		assertEquals(new NpcAction.GatherWood(10L, 20L, 2), plan("наруби 2 бревна").action().orElseThrow());
	}

	@Test
	void missingCandidatesDeclineWithAReason() {
		IntentCandidates noLogs = new IntentCandidates(List.of(), List.of(CHEST), Optional.of(PLAYER), Map.of());
		assertEquals(Optional.of(Intent.REPLY_NO_TREES), planner.planNow(req("gather wood", noLogs, false)).orElseThrow().replyKey());
		IntentCandidates noChest = new IntentCandidates(List.of(LOG_NEAR), List.of(), Optional.of(PLAYER), Map.of());
		assertEquals(Optional.of(Intent.REPLY_NO_CONTAINER), planner.planNow(req("gather wood", noChest, false)).orElseThrow().replyKey());
		assertEquals(Optional.of(Intent.REPLY_NO_CONTAINER), planner.planNow(req("put everything away", noChest, false)).orElseThrow().replyKey());
		IntentCandidates noPlayer = new IntentCandidates(List.of(), List.of(), Optional.empty(), Map.of());
		assertEquals(Optional.of(Intent.REPLY_WHERE), planner.planNow(req("come here", noPlayer, false)).orElseThrow().replyKey());
	}

	@Test
	void withdrawMatchesItemsTheContainerActuallyHolds() {
		assertEquals(new NpcAction.Withdraw(20L, "minecraft:cobblestone", 10), plan("bring me 10 cobblestone").action().orElseThrow());
		assertEquals(new NpcAction.Withdraw(20L, "minecraft:oak_log", 1), plan("hand me an oak please").action().orElseThrow());
		assertEquals(new NpcAction.Withdraw(20L, "minecraft:cobblestone", 64), plan("принеси 999 cobblestone").action().orElseThrow());
		// "bring wood" is gathering, not withdrawing
		assertTrue(plan("bring me wood").action().orElseThrow() instanceof NpcAction.GatherWood);
		// an item the NPC holds but no container has: a clear refusal
		assertEquals(Optional.of(Intent.REPLY_NO_SUCH_ITEM), plan("give me a stick").replyKey());
	}

	@Test
	void depositAndComeHere() {
		assertEquals(new NpcAction.Deposit(20L), plan("please put it away in the chest").action().orElseThrow());
		assertEquals(new NpcAction.Deposit(20L), plan("сложи всё в сундук").action().orElseThrow());
		assertEquals(new NpcAction.MoveTo(30L), plan("come here").action().orElseThrow());
		assertEquals(new NpcAction.MoveTo(30L), plan("подойди ко мне").action().orElseThrow());
	}

	@Test
	void smallTalkIsNotWorkAndBusyNpcsDecline() {
		assertTrue(planner.planNow(req("hello, how are you?", full(), false)).isEmpty());
		assertTrue(planner.planNow(req("what do you know about the ore?", full(), false)).isEmpty());
		assertEquals(Optional.of(Intent.REPLY_BUSY), planner.planNow(req("chop some wood", full(), true)).orElseThrow().replyKey());
		assertTrue(planner.planNow(req("chop some wood", full(), true)).orElseThrow().action().isEmpty());
	}
}
