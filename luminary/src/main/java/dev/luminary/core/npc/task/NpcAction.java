package dev.luminary.core.npc.task;

/**
 * Typed, server-validated physical operations. Arguments are plain data (packed block positions, counts, item ids),
 * never world objects, so an action can come from a command, a data pack or an untrusted planner and still be
 * validated the same way. New physical actions are added here and get their own validators and executor phases.
 */
public sealed interface NpcAction {
	/**
	 * Break up to {@code maxLogs} log blocks starting at {@code targetPos} (continuing straight up the trunk), collect
	 * the real drops and deposit them into the authorized container at {@code containerPos}.
	 */
	record GatherWood(long targetPos, long containerPos, int maxLogs) implements NpcAction {}

	/** Walk to within reach of {@code targetPos} and stop. */
	record MoveTo(long targetPos) implements NpcAction {}

	/** Put everything the NPC carries into the authorized container at {@code containerPos}. */
	record Deposit(long containerPos) implements NpcAction {}

	/** Take up to {@code count} of {@code itemId} out of the authorized container at {@code containerPos} into the NPC's own inventory. */
	record Withdraw(long containerPos, String itemId, int count) implements NpcAction {}

	/**
	 * Take up to {@code count} of {@code inputItemId} and enough fuel from the authorized container, load the furnace at
	 * {@code furnacePos}, wait for the real smelting, collect the output and put it (and leftover fuel) back into the container.
	 */
	record Smelt(long furnacePos, long containerPos, String inputItemId, int count) implements NpcAction {}

	/** Stable id used in persisted task records, planner output and command output. */
	default String type() {
		if (this instanceof GatherWood) {
			return "gather_wood";
		}
		if (this instanceof MoveTo) {
			return "move_to";
		}
		if (this instanceof Deposit) {
			return "deposit";
		}
		if (this instanceof Withdraw) {
			return "withdraw";
		}
		if (this instanceof Smelt) {
			return "smelt";
		}
		throw new IllegalStateException("unnamed action " + getClass());
	}
}
