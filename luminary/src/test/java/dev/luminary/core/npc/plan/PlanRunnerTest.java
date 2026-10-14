package dev.luminary.core.npc.plan;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlanRunnerTest {
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");

	/** A world where assignments succeed and tasks finish when the test says so. */
	static final class FakeWorld implements PlanRunner.World {
		NpcTask current;
		int containerLogs;
		int cargo;
		final List<NpcAction> assigned = new ArrayList<>();
		boolean refuse;

		@Override
		public boolean hasActiveTask() {
			return current != null && current.isActive();
		}

		@Override
		public Optional<NpcTask> task(UUID id) {
			return Optional.ofNullable(current).filter(t -> t.id().equals(id));
		}

		@Override
		public Optional<NpcTask> currentTask() {
			return Optional.ofNullable(current);
		}

		@Override
		public int containerCount(long containerPos, String itemId) {
			return containerLogs;
		}

		@Override
		public int cargoCount() {
			return cargo;
		}

		@Override
		public Result assign(NpcAction action, UUID requestedBy) {
			if (refuse) {
				return Result.fail("luminary.task.fail.test");
			}
			assigned.add(action);
			current = new NpcTask(UUID.randomUUID(), action, Instant.EPOCH, requestedBy);
			return Result.OK;
		}

		void finish() {
			current.complete();
		}

		void fail() {
			current.fail("boom");
		}
	}

	@Test
	void stepsRunInOrderAndOnlyOnce() {
		FakeWorld w = new FakeWorld();
		NpcPlan plan = PlanTemplates.stockWood(1L, 2L, 4, t0, null);
		PlanRunner r = new PlanRunner();
		assertEquals(PlanRunner.Outcome.STARTED, r.tick(plan, w).outcome());
		assertEquals(1, w.assigned.size());
		assertEquals(PlanRunner.Outcome.WAITING, r.tick(plan, w).outcome());
		assertEquals(PlanRunner.Outcome.WAITING, r.tick(plan, w).outcome());
		assertEquals(1, w.assigned.size(), "a running step is never re-issued");
		w.finish();
		w.cargo = 3;   // the gather left something in hand
		assertEquals(PlanRunner.Outcome.STEP_DONE, r.tick(plan, w).outcome());
		assertEquals(PlanStep.Status.DONE, plan.steps().get(0).status());
		assertEquals(PlanRunner.Outcome.STARTED, r.tick(plan, w).outcome());
		assertTrue(w.assigned.get(1) instanceof NpcAction.Deposit);
		w.finish();
		assertEquals(PlanRunner.Outcome.PLAN_COMPLETED, r.tick(plan, w).outcome());
		assertEquals(NpcPlan.Status.COMPLETED, plan.status());
		assertEquals(PlanRunner.Outcome.IDLE, r.tick(plan, w).outcome());
		assertEquals(2, w.assigned.size());
		// a gather that already deposited everything: the deposit step is skipped, not failed
		FakeWorld w2 = new FakeWorld();
		NpcPlan plan2 = PlanTemplates.stockWood(1L, 2L, 4, t0, null);
		r.tick(plan2, w2);
		w2.finish();
		r.tick(plan2, w2);
		assertEquals(PlanRunner.Outcome.PLAN_COMPLETED, r.tick(plan2, w2).outcome());
		assertEquals(PlanStep.Status.SKIPPED, plan2.steps().get(1).status());
	}

	@Test
	void failuresAreRetriedABoundedNumberOfTimesThenThePlanFails() {
		FakeWorld w = new FakeWorld();
		NpcPlan plan = PlanTemplates.stockWood(1L, 2L, 4, t0, null);
		PlanRunner r = new PlanRunner(2);
		r.tick(plan, w);
		w.fail();
		assertEquals(PlanRunner.Outcome.STEP_FAILED, r.tick(plan, w).outcome());
		assertEquals(PlanStep.Status.PENDING, plan.steps().get(0).status(), "one attempt left");
		assertEquals(PlanRunner.Outcome.STARTED, r.tick(plan, w).outcome());
		w.fail();
		PlanRunner.Tick t = r.tick(plan, w);
		assertEquals(PlanRunner.Outcome.PLAN_FAILED, t.outcome());
		assertEquals(NpcPlan.Status.FAILED, plan.status());
		assertTrue(plan.failure().orElse("").contains("boom"));
		assertEquals(2, w.assigned.size());
	}

	@Test
	void preconditionsWaitForOtherTasksAndSkipImpossibleSteps() {
		FakeWorld w = new FakeWorld();
		w.current = new NpcTask(UUID.randomUUID(), new NpcAction.MoveTo(5L), Instant.EPOCH, null);   // someone else's task
		NpcPlan plan = PlanTemplates.smeltFromStock(2L, 3L, "minecraft:raw_iron", 4, t0, UUID.randomUUID());
		PlanRunner r = new PlanRunner();
		assertEquals(PlanRunner.Outcome.WAITING, r.tick(plan, w).outcome());
		w.current.complete();
		w.containerLogs = 0;
		PlanRunner.Tick t = r.tick(plan, w);
		assertEquals(PlanRunner.Outcome.STEP_FAILED, t.outcome());
		assertEquals(PlanStep.Status.SKIPPED, plan.steps().get(0).status(), "nothing to smelt: skipped, not looped");
		w.cargo = 2;
		assertEquals(PlanRunner.Outcome.STARTED, r.tick(plan, w).outcome(), "the deposit step still runs when something is carried");
		w.finish();
		assertEquals(PlanRunner.Outcome.PLAN_COMPLETED, r.tick(plan, w).outcome());
	}

	@Test
	void refusedAssignmentsCountAsAttempts() {
		FakeWorld w = new FakeWorld();
		w.refuse = true;
		NpcPlan plan = PlanTemplates.stockWood(1L, 2L, 4, t0, null);
		PlanRunner r = new PlanRunner(2);
		assertEquals(PlanRunner.Outcome.STEP_FAILED, r.tick(plan, w).outcome());
		assertEquals(PlanRunner.Outcome.PLAN_FAILED, r.tick(plan, w).outcome());
		assertTrue(w.assigned.isEmpty());
	}
}
