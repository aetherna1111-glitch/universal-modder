package dev.luminary.core.npc.autonomy;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.NpcStateCodec;
import dev.luminary.core.npc.intent.IntentCandidates;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.TaskRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyPlannerTest {
	static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
	static final long CHEST = 20L, LOG_A = 10L, LOG_B = 11L;
	static final IntentCandidates.Spot SPOT_A = new IntentCandidates.Spot(LOG_A, 1, 2, 3, 9.0), SPOT_B = new IntentCandidates.Spot(LOG_B, 5, 2, 3, 25.0);
	final AutonomyPlanner planner = new AutonomyPlanner(new AutonomyRules(100, 3, Duration.ofHours(1), Duration.ofMinutes(10)), TaskRules.DEFAULTS);

	static IntentCandidates seen(int logsInChest, int held) {
		return new IntentCandidates(List.of(SPOT_A, SPOT_B),
				List.of(new IntentCandidates.ContainerInfo(new IntentCandidates.Spot(CHEST, 4, 2, 4, 16.0), logsInChest > 0 ? Map.of("minecraft:oak_log", logsInChest) : Map.of())),
				Optional.empty(), held > 0 ? Map.of("minecraft:oak_log", held) : Map.of());
	}

	AutonomyState enabledWithOrder(int min) {
		AutonomyState s = new AutonomyState();
		s.setEnabled(true);
		s.addOrder(new StandingOrder.StockWood(CHEST, min));
		return s;
	}

	@Test
	void disabledBusyAndSatisfiedNpcsStayIdle() {
		AutonomyState off = new AutonomyState();
		off.addOrder(new StandingOrder.StockWood(CHEST, 8));
		assertEquals(AutonomyPlanner.REASON_DISABLED, planner.decide(off, seen(0, 0), false, NOW).reason());
		assertEquals(AutonomyPlanner.REASON_BUSY, planner.decide(enabledWithOrder(8), seen(0, 0), true, NOW).reason());
		assertEquals(AutonomyPlanner.REASON_NOTHING, planner.decide(enabledWithOrder(8), seen(8, 0), false, NOW).reason());
		AutonomyState noOrders = new AutonomyState();
		noOrders.setEnabled(true);
		assertEquals(AutonomyPlanner.REASON_NOTHING, planner.decide(noOrders, seen(0, 0), false, NOW).reason());
	}

	@Test
	void standingOrderGathersTheShortfallFromTheNearestTrunk() {
		AutonomyPlanner.Decision d = planner.decide(enabledWithOrder(8), seen(5, 0), false, NOW);
		assertEquals(AutonomyPlanner.REASON_ORDER_STOCK_WOOD, d.reason());
		assertEquals(new NpcAction.GatherWood(LOG_A, CHEST, 3), d.action().orElseThrow());
		assertEquals(16, ((NpcAction.GatherWood) planner.decide(enabledWithOrder(100), seen(0, 0), false, NOW).action().orElseThrow()).maxLogs(), "clamped to the task rules");
		// stripped and modded logs count as stock too
		IntentCandidates mixed = new IntentCandidates(List.of(SPOT_A), List.of(new IntentCandidates.ContainerInfo(new IntentCandidates.Spot(CHEST, 4, 2, 4, 16.0),
				Map.of("minecraft:stripped_birch_log", 4, "minecraft:crimson_stem", 2, "minecraft:cobblestone", 50))), Optional.empty(), Map.of());
		assertEquals(AutonomyPlanner.REASON_NOTHING, planner.decide(enabledWithOrder(6), mixed, false, NOW).reason());
	}

	@Test
	void reflexEmptiesHandsBeforeAnyOrder() {
		AutonomyPlanner.Decision d = planner.decide(enabledWithOrder(8), seen(0, 3), false, NOW);
		assertEquals(AutonomyPlanner.REASON_REFLEX_EMPTY_HANDS, d.reason());
		assertEquals(new NpcAction.Deposit(CHEST), d.action().orElseThrow());
	}

	@Test
	void avoidedTargetsAreSkippedUntilTheCooldownEnds() {
		AutonomyState s = enabledWithOrder(8);
		s.avoid(LOG_A, NOW.plus(Duration.ofMinutes(10)));
		assertEquals(LOG_B, ((NpcAction.GatherWood) planner.decide(s, seen(0, 0), false, NOW).action().orElseThrow()).targetPos());
		s.avoid(LOG_B, NOW.plus(Duration.ofMinutes(10)));
		assertEquals(AutonomyPlanner.REASON_NOTHING, planner.decide(s, seen(0, 0), false, NOW).reason());
		Instant later = NOW.plus(Duration.ofMinutes(11));
		assertTrue(planner.decide(s, seen(0, 0), false, later).action().isPresent(), "cooldown over");
		s.prune(later);
		assertTrue(s.avoidUntil().isEmpty());
		// an avoided container silences the order and the reflex alike
		AutonomyState c = enabledWithOrder(8);
		c.avoid(CHEST, NOW.plus(Duration.ofMinutes(10)));
		assertEquals(AutonomyPlanner.REASON_NOTHING, planner.decide(c, seen(0, 3), false, NOW).reason());
	}

	@Test
	void budgetCapsDecisionsPerWindowAndSlidesForward() {
		AutonomyState s = enabledWithOrder(8);
		for (int i = 0; i < 3; i++) {
			assertTrue(planner.decide(s, seen(0, 0), false, NOW).action().isPresent());
			s.recordDecision(NOW.plusSeconds(i), Duration.ofHours(1));
		}
		assertEquals(AutonomyPlanner.REASON_BUDGET, planner.decide(s, seen(0, 0), false, NOW.plusSeconds(10)).reason());
		assertTrue(planner.decide(s, seen(0, 0), false, NOW.plus(Duration.ofMinutes(61))).action().isPresent(), "window slid past the old decisions");
	}

	@Test
	void autonomyStateSurvivesTheCodec() {
		LuminaryId ai = LuminaryId.of("npc/ai");
		NpcRoster r = new NpcRoster();
		AutonomyState s = r.ensureRecord(ai).autonomy();
		s.setEnabled(true);
		assertTrue(s.addOrder(new StandingOrder.StockWood(CHEST, 8)));
		assertFalse(s.addOrder(new StandingOrder.StockWood(CHEST, 8)), "no duplicate orders");
		s.recordDecision(NOW, Duration.ofHours(1));
		s.avoid(LOG_A, NOW.plusSeconds(600));
		NpcStateCodec codec = NpcStateCodec.production();
		String json = codec.encode(r);
		AutonomyState copy = codec.decode(json).get(ai).orElseThrow().autonomy();
		assertTrue(copy.enabled());
		assertEquals(List.of(new StandingOrder.StockWood(CHEST, 8)), copy.orders());
		assertEquals(1, copy.decisionsWithin(Duration.ofHours(1), NOW));
		assertTrue(copy.isAvoided(LOG_A, NOW));
		assertEquals(json, codec.encode(codec.decode(json)));
		// a record without autonomy decodes to the defaults (off, nothing)
		NpcRoster plain = codec.decode("{\"schemaVersion\":1,\"npcs\":{\"luminary:npc/x\":{\"pos\":0,\"players\":{}}}}");
		assertFalse(plain.get(LuminaryId.of("npc/x")).orElseThrow().autonomy().enabled());
	}
}
