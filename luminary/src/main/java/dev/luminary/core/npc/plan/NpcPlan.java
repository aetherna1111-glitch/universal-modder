package dev.luminary.core.npc.plan;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A persistent multi-step plan with a goal in words, ordered steps, and a terminal status. Replanning is bounded:
 * at most {@code maxReplans} times the plan may append or substitute steps (the runner does this for known
 * failures); then it fails loudly instead of looping.
 */
public final class NpcPlan {
	public enum Status { ACTIVE, COMPLETED, FAILED, CANCELLED }

	public static final int DEFAULT_MAX_ATTEMPTS = 2;
	public static final int DEFAULT_MAX_REPLANS = 2;

	private final UUID id;
	private final String goal;
	private final List<PlanStep> steps;
	private final Instant createdAt;
	private final UUID requestedBy;
	private Status status = Status.ACTIVE;
	private int replans;
	private String failure;

	public NpcPlan(UUID id, String goal, List<PlanStep> steps, Instant createdAt, UUID requestedBy) {
		this.id = Objects.requireNonNull(id);
		this.goal = Objects.requireNonNull(goal);
		this.steps = new java.util.ArrayList<>(steps);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.requestedBy = requestedBy;
		if (this.steps.isEmpty()) {
			throw new IllegalArgumentException("a plan needs at least one step");
		}
	}

	public UUID id() {
		return id;
	}

	public String goal() {
		return goal;
	}

	public List<PlanStep> steps() {
		return Collections.unmodifiableList(steps);
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Optional<UUID> requestedBy() {
		return Optional.ofNullable(requestedBy);
	}

	public Status status() {
		return status;
	}

	public int replans() {
		return replans;
	}

	public Optional<String> failure() {
		return Optional.ofNullable(failure);
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	/** The step currently running, if any. */
	public Optional<PlanStep> running() {
		return steps.stream().filter(s -> s.status() == PlanStep.Status.RUNNING).findFirst();
	}

	/** The first step still to do. */
	public Optional<PlanStep> next() {
		return steps.stream().filter(s -> s.status() == PlanStep.Status.PENDING).findFirst();
	}

	public Optional<PlanStep> step(String stepId) {
		return steps.stream().filter(s -> s.id().equals(stepId)).findFirst();
	}

	void complete() {
		status = Status.COMPLETED;
	}

	void fail(String reason) {
		status = Status.FAILED;
		failure = reason;
	}

	public void cancel() {
		if (status == Status.ACTIVE) {
			status = Status.CANCELLED;
		}
	}

	/** Inserts {@code step} right after the step {@code afterId} (bounded replanning). False when the budget is spent. */
	boolean insertAfter(String afterId, PlanStep step) {
		if (replans >= DEFAULT_MAX_REPLANS) {
			return false;
		}
		int i = 0;
		for (; i < steps.size(); i++) {
			if (steps.get(i).id().equals(afterId)) {
				break;
			}
		}
		steps.add(Math.min(i + 1, steps.size()), step);
		replans++;
		return true;
	}

	/** Codec only. */
	public void restore(Status status, int replans, String failure) {
		this.status = status;
		this.replans = replans;
		this.failure = failure;
	}
}
