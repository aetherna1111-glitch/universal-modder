package dev.luminary.core.npc.task;

/**
 * Typed, server-validated physical operations. Arguments are plain data (packed block positions, counts), never
 * world objects, so an action can come from a command, a data pack or, later, an untrusted planner and still be
 * validated the same way. New physical actions are added here and get their own validators and executor phases.
 */
public sealed interface NpcAction {
	/**
	 * Break up to {@code maxLogs} log blocks starting at {@code targetPos} (continuing straight up the trunk), collect
	 * the real drops and deposit them into the authorized container at {@code containerPos}.
	 */
	record GatherWood(long targetPos, long containerPos, int maxLogs) implements NpcAction {}

	/** Stable id used in persisted task records and command output. */
	default String type() {
		if (this instanceof GatherWood) {
			return "gather_wood";
		}
		throw new IllegalStateException("unnamed action " + getClass());
	}
}
