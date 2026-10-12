package dev.luminary.core.npc.task;

/** Explicit lifecycle of a task. Terminal states never change again; a new task gets a new id. */
public enum TaskState {
	VALIDATED,
	MOVING_TO_TARGET,
	BREAKING,
	COLLECTING,
	MOVING_TO_CONTAINER,
	DEPOSITING,
	COMPLETED,
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}
}
