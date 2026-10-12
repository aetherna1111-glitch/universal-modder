package dev.luminary.core.npc.task;

/**
 * What validators may ask about the world, answered by the Fabric layer from authoritative server state. Keeping the
 * questions this narrow is what makes validation unit-testable and keeps the executor from being the only check.
 */
public interface ActionContext {
	enum BlockKind { AIR, LOG, CONTAINER, OTHER }

	/** Whether the chunk holding {@code pos} is loaded; unloaded positions are never valid targets. */
	boolean isLoaded(long pos);

	BlockKind kindAt(long pos);

	/** Squared distance from the NPC body to the block center. */
	double distanceSq(long pos);

	/** The NPC's record explicitly lists this container position. */
	boolean containerAuthorized(long pos);

	/** Free slots in the container (0 for a full one, -1 when it is not an inventory). */
	int containerFreeSlots(long pos);

	/** The NPC may act in its current world/context (dimension allow-list, future region rules). */
	boolean permitted(NpcAction action);
}
