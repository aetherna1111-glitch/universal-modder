package dev.luminary.core.npc.task;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A task owned by one NPC. Mutable progress (state, phase counters) lives here and is persisted with the roster so a
 * reload resumes exactly where the executor stopped. Transitions are idempotent: a terminal state is final, and
 * progress counters only move forward.
 */
public final class NpcTask {
	public static final String FAIL_CANCELLED = "cancelled";
	public static final String FAIL_TARGET_CHANGED = "target_changed";
	public static final String FAIL_UNREACHABLE = "unreachable";
	public static final String FAIL_CONTAINER_INVALID = "container_invalid";
	public static final String FAIL_CONTAINER_FULL = "container_full";
	public static final String FAIL_NOTHING_COLLECTED = "nothing_collected";
	public static final String FAIL_BODY_LOST = "body_lost";

	private final UUID id;
	private final NpcAction action;
	private final Instant createdAt;
	private TaskState state;
	private String failureReason;
	private final TaskLedger ledger;
	/** Executor scratch persisted for resumption: the block currently being broken and progress ticks in the phase. */
	private long currentTarget;
	private int phaseTicks;
	private int idleTicks;

	public NpcTask(UUID id, NpcAction action, Instant createdAt) {
		this(id, action, createdAt, TaskState.VALIDATED, null, new TaskLedger(), 0L, 0, 0);
	}

	public NpcTask(UUID id, NpcAction action, Instant createdAt, TaskState state, String failureReason, TaskLedger ledger,
				   long currentTarget, int phaseTicks, int idleTicks) {
		this.id = Objects.requireNonNull(id);
		this.action = Objects.requireNonNull(action);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.state = Objects.requireNonNull(state);
		this.failureReason = failureReason;
		this.ledger = Objects.requireNonNull(ledger);
		this.currentTarget = currentTarget;
		this.phaseTicks = phaseTicks;
		this.idleTicks = idleTicks;
	}

	public UUID id() {
		return id;
	}

	public NpcAction action() {
		return action;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public TaskState state() {
		return state;
	}

	public Optional<String> failureReason() {
		return Optional.ofNullable(failureReason);
	}

	public TaskLedger ledger() {
		return ledger;
	}

	public long currentTarget() {
		return currentTarget;
	}

	public int phaseTicks() {
		return phaseTicks;
	}

	public int idleTicks() {
		return idleTicks;
	}

	public boolean isActive() {
		return !state.isTerminal();
	}

	/** Moves to a non-terminal state and resets phase counters. No-op once terminal. */
	public boolean enter(TaskState next) {
		if (state.isTerminal() || next.isTerminal()) {
			return false;
		}
		if (next != state) {
			state = next;
			phaseTicks = 0;
			idleTicks = 0;
		}
		return true;
	}

	public boolean complete() {
		if (state.isTerminal()) {
			return false;
		}
		state = TaskState.COMPLETED;
		return true;
	}

	public boolean fail(String reason) {
		if (state.isTerminal()) {
			return false;
		}
		state = TaskState.FAILED;
		failureReason = Objects.requireNonNull(reason);
		return true;
	}

	public void setCurrentTarget(long pos) {
		this.currentTarget = pos;
	}

	public int tickPhase() {
		return ++phaseTicks;
	}

	public int tickIdle() {
		return ++idleTicks;
	}

	public void resetIdle() {
		idleTicks = 0;
	}
}
