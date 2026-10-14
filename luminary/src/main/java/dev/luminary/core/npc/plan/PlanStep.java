package dev.luminary.core.npc.plan;

import dev.luminary.core.npc.task.NpcAction;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One step of a {@link NpcPlan}: an action, how it is verified, and its progress. A step runs at most once at a
 * time and is marked DONE only after its task completed (the ledger is the verification), so a reload or restart
 * never executes it twice; a failed step is retried up to {@code maxAttempts} before the plan is replanned or fails.
 */
public final class PlanStep {
	public enum Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }

	private final String id;
	private final NpcAction action;
	private final Precondition precondition;
	private Status status = Status.PENDING;
	private int attempts;
	private UUID taskId;
	private String lastFailure;

	public PlanStep(String id, NpcAction action, Precondition precondition) {
		this.id = Objects.requireNonNull(id);
		this.action = Objects.requireNonNull(action);
		this.precondition = Objects.requireNonNull(precondition);
	}

	public String id() {
		return id;
	}

	public NpcAction action() {
		return action;
	}

	public Precondition precondition() {
		return precondition;
	}

	public Status status() {
		return status;
	}

	public int attempts() {
		return attempts;
	}

	public Optional<UUID> taskId() {
		return Optional.ofNullable(taskId);
	}

	public Optional<String> lastFailure() {
		return Optional.ofNullable(lastFailure);
	}

	void started(UUID task) {
		status = Status.RUNNING;
		attempts++;
		taskId = task;
	}

	void done() {
		status = Status.DONE;
	}

	void failed(String reason, int maxAttempts) {
		lastFailure = reason;
		status = attempts >= maxAttempts ? Status.FAILED : Status.PENDING;
	}

	/** The assignment itself was refused: counts as an attempt (nothing ran). */
	void refused(String reason, int maxAttempts) {
		attempts++;
		failed(reason, maxAttempts);
	}

	void skipped(String reason) {
		lastFailure = reason;
		status = Status.SKIPPED;
	}

	/** Codec only. */
	public void restore(Status status, int attempts, UUID taskId, String lastFailure) {
		this.status = status;
		this.attempts = attempts;
		this.taskId = taskId;
		this.lastFailure = lastFailure;
	}

	/** What must hold before the step starts; evaluated against the live world by the runner's context. */
	public sealed interface Precondition permits Precondition.None, Precondition.ContainerHas, Precondition.HandsEmpty, Precondition.Carrying {
		record None() implements Precondition {
		}

		/** The container holds at least {@code count} of {@code itemId}; otherwise the step waits or is skipped. */
		record ContainerHas(long containerPos, String itemId, int count) implements Precondition {
		}

		/** The NPC carries nothing (cargo), e.g. before gathering. */
		record HandsEmpty() implements Precondition {
		}

		/** The NPC carries something to deposit; otherwise the step is skipped as already satisfied. */
		record Carrying() implements Precondition {
		}
	}
}
