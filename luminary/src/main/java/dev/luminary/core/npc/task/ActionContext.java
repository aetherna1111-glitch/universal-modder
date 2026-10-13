package dev.luminary.core.npc.task;

/**
 * What validators may ask about the world, answered by the Fabric layer from authoritative server state. Keeping the
 * questions this narrow is what makes validation unit-testable and keeps the executor from being the only check.
 */
public interface ActionContext {
	enum BlockKind { AIR, LOG, CONTAINER, FURNACE, OTHER }

	/** Whether the chunk holding {@code pos} is loaded; unloaded positions are never valid targets. */
	boolean isLoaded(long pos);

	BlockKind kindAt(long pos);

	/** Squared distance from the NPC body to the block center. */
	double distanceSq(long pos);

	/** The NPC's record explicitly lists this container position. */
	boolean containerAuthorized(long pos);

	/** Free slots in the container (0 for a full one, -1 when it is not an inventory). */
	int containerFreeSlots(long pos);

	/** How many of {@code itemId} the container holds (-1 when it is not an inventory). */
	int containerCount(long pos, String itemId);

	/** Whether {@code itemId} names a registered item. */
	boolean itemExists(String itemId);

	/** Whether a furnace recipe turns {@code itemId} into something. */
	boolean smeltable(String itemId);

	/** Total burn ticks the fuel items in the container would give (0 when none, -1 when it is not an inventory). */
	int fuelTicksIn(long containerPos);

	/** The furnace can take {@code itemId} into its input slot right now (empty, or the same item with room). */
	boolean furnaceAcceptsInput(long furnacePos, String itemId);

	/** Items the NPC carries right now (all stacks). */
	int heldItemCount();

	/** Free slots in the NPC's own inventory. */
	int heldFreeSlots();

	/** The NPC may act in its current world/context (dimension allow-list, future region rules). */
	boolean permitted(NpcAction action);
}
