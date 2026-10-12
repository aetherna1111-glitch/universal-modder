package dev.luminary.core.npc.intent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the Fabric layer saw around the NPC when a request arrived: the only positions and items a planner may use.
 * A planner (keyword rules or a model) chooses among these; it can never name an arbitrary coordinate. Positions
 * carry the packed form the actions need and the x/y/z the prompt and the player read.
 */
public record IntentCandidates(List<Spot> logs, List<ContainerInfo> containers, Optional<Spot> playerPos, Map<String, Integer> held) {
	public static final int MAX_LOGS = 8;
	public static final int MAX_CONTAINERS = 6;
	public static final int MAX_ITEMS_PER_CONTAINER = 8;

	/** A block position with its squared distance from the NPC. */
	public record Spot(long pos, int x, int y, int z, double distanceSq) {
		public String coords() {
			return x + " " + y + " " + z;
		}
	}

	/** An authorized, loaded container and a bounded summary of its contents (item id → count). */
	public record ContainerInfo(Spot spot, Map<String, Integer> contents) {}

	public static final IntentCandidates NONE = new IntentCandidates(List.of(), List.of(), Optional.empty(), Map.of());

	public IntentCandidates {
		logs = logs.stream().sorted(Comparator.comparingDouble(Spot::distanceSq)).limit(MAX_LOGS).toList();
		containers = containers.stream().sorted(Comparator.comparingDouble(c -> c.spot().distanceSq())).limit(MAX_CONTAINERS).toList();
		held = Map.copyOf(held);
	}

	public Optional<Spot> nearestLog() {
		return logs.stream().findFirst();
	}

	public Optional<ContainerInfo> nearestContainer() {
		return containers.stream().findFirst();
	}

	public int heldTotal() {
		return held.values().stream().mapToInt(Integer::intValue).sum();
	}
}
