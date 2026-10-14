package dev.luminary.core.npc.plan;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskState;

import java.util.Optional;
import java.util.UUID;

/**
 * Drives one plan through the existing validated task path. Pure: the caller supplies the world view
 * ({@link World}) and the assignment function. Idempotent per tick: a running step is only observed, never
 * re-issued; a DONE step is never repeated; preconditions that do not hold yet make the runner wait, and a
 * precondition that can never hold (nothing to withdraw) is turned into a skip, never a loop.
 */
public final class PlanRunner {
	/** What the runner may see of the world and the NPC. */
	public interface World {
		boolean hasActiveTask();

		/** The task with this id, if it is the current or last task. */
		Optional<NpcTask> task(UUID id);

		/** The current (or last) task, whatever its id. */
		Optional<NpcTask> currentTask();

		int containerCount(long containerPos, String itemId);

		int cargoCount();

		Result assign(NpcAction action, UUID requestedBy);
	}

	public enum Outcome { IDLE, WAITING, STARTED, STEP_DONE, STEP_FAILED, PLAN_COMPLETED, PLAN_FAILED }

	public record Tick(Outcome outcome, Optional<PlanStep> step, Optional<String> detail) {
		static Tick of(Outcome o) {
			return new Tick(o, Optional.empty(), Optional.empty());
		}

		static Tick of(Outcome o, PlanStep s, String detail) {
			return new Tick(o, Optional.of(s), Optional.ofNullable(detail));
		}
	}

	private final int maxAttempts;

	public PlanRunner() {
		this(NpcPlan.DEFAULT_MAX_ATTEMPTS);
	}

	public PlanRunner(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public Tick tick(NpcPlan plan, World world) {
		if (!plan.isActive()) {
			return Tick.of(Outcome.IDLE);
		}
		Optional<PlanStep> running = plan.running();
		if (running.isPresent()) {
			PlanStep step = running.get();
			Optional<NpcTask> task = step.taskId().flatMap(world::task);
			if (task.isEmpty()) {
				// the task record is gone (replaced by someone else's task): treat as a failure, bounded by attempts
				step.failed("task record lost", maxAttempts);
				return afterFailure(plan, step, "task record lost");
			}
			NpcTask t = task.get();
			if (t.isActive()) {
				return Tick.of(Outcome.WAITING, step, null);
			}
			if (t.state() == TaskState.COMPLETED) {
				step.done();
				if (plan.next().isEmpty()) {
					plan.complete();
					return Tick.of(Outcome.PLAN_COMPLETED, step, null);
				}
				return Tick.of(Outcome.STEP_DONE, step, null);
			}
			String reason = t.failureReason().orElse("failed");
			step.failed(reason, maxAttempts);
			return afterFailure(plan, step, reason);
		}
		Optional<PlanStep> next = plan.next();
		if (next.isEmpty()) {
			plan.complete();
			return Tick.of(Outcome.PLAN_COMPLETED);
		}
		if (world.hasActiveTask()) {
			return Tick.of(Outcome.WAITING, next.get(), "another task is running");
		}
		PlanStep step = next.get();
		Optional<String> blocked = blocked(step.precondition(), world);
		if (blocked.isPresent()) {
			step.skipped(blocked.get());
			if (plan.next().isEmpty()) {
				plan.complete();
				return Tick.of(Outcome.PLAN_COMPLETED, step, blocked.get());
			}
			return Tick.of(Outcome.STEP_FAILED, step, blocked.get());
		}
		Result r = world.assign(step.action(), plan.requestedBy().orElse(null));
		if (!r.ok()) {
			step.refused(r.reasonKey(), maxAttempts);
			return afterFailure(plan, step, r.reasonKey());
		}
		// the assignment created the current task: it is the only active one
		UUID taskId = world.currentTask().map(NpcTask::id).orElse(null);
		step.started(taskId);
		return Tick.of(Outcome.STARTED, step, null);
	}

	private Tick afterFailure(NpcPlan plan, PlanStep step, String reason) {
		if (step.status() == PlanStep.Status.FAILED) {
			plan.fail("step " + step.id() + ": " + reason);
			return Tick.of(Outcome.PLAN_FAILED, step, reason);
		}
		return Tick.of(Outcome.STEP_FAILED, step, reason);
	}

	/** A precondition that cannot hold given the world: the reason; empty when the step may start. */
	private static Optional<String> blocked(PlanStep.Precondition p, World world) {
		if (p instanceof PlanStep.Precondition.ContainerHas c) {
			int have = world.containerCount(c.containerPos(), c.itemId());
			return have >= c.count() ? Optional.empty() : Optional.of("container has " + have + " " + c.itemId() + ", needs " + c.count());
		}
		if (p instanceof PlanStep.Precondition.HandsEmpty) {
			return world.cargoCount() == 0 ? Optional.empty() : Optional.of("hands not empty");
		}
		if (p instanceof PlanStep.Precondition.Carrying) {
			return world.cargoCount() > 0 ? Optional.empty() : Optional.of("nothing carried; already deposited");
		}
		return Optional.empty();
	}
}
