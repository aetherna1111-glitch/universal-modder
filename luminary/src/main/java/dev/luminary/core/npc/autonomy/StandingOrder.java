package dev.luminary.core.npc.autonomy;

/**
 * A durable goal an administrator (or, later, a player the NPC trusts) gives the NPC. Orders are plain data and are
 * persisted with the roster; the autonomy planner turns them into ordinary validated tasks when the world calls for it.
 */
public sealed interface StandingOrder {
	/** Keep at least {@code minLogs} log items in the authorized container at {@code containerPos}, cutting nearby trees. */
	record StockWood(long containerPos, int minLogs) implements StandingOrder {}

	default String type() {
		if (this instanceof StockWood) {
			return "stock_wood";
		}
		throw new IllegalStateException("unnamed order " + getClass());
	}
}
