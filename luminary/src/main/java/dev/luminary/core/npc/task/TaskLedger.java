package dev.luminary.core.npc.task;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item accounting for one task. Every count is incremented only by an executor step that actually changed the world
 * (a block broken, an item entity absorbed, a stack inserted), so {@code deposited ≤ collected ≤ real drops} holds by
 * construction and can be asserted by tests from the outside.
 */
public final class TaskLedger {
	private int blocksBroken;
	private final Map<String, Integer> collected = new TreeMap<>();
	private final Map<String, Integer> deposited = new TreeMap<>();
	private final Map<String, Integer> withdrawn = new TreeMap<>();
	private final Map<String, Integer> smelted = new TreeMap<>();

	public int blocksBroken() {
		return blocksBroken;
	}

	public Map<String, Integer> collected() {
		return Collections.unmodifiableMap(collected);
	}

	public Map<String, Integer> deposited() {
		return Collections.unmodifiableMap(deposited);
	}

	public Map<String, Integer> withdrawn() {
		return Collections.unmodifiableMap(withdrawn);
	}

	public Map<String, Integer> smelted() {
		return Collections.unmodifiableMap(smelted);
	}

	public int smeltedTotal() {
		return smelted.values().stream().mapToInt(Integer::intValue).sum();
	}

	public void smelted(String itemId, int count) {
		if (count > 0) {
			smelted.merge(itemId, count, Integer::sum);
		}
	}

	public int withdrawnTotal() {
		return withdrawn.values().stream().mapToInt(Integer::intValue).sum();
	}

	public int collectedTotal() {
		return collected.values().stream().mapToInt(Integer::intValue).sum();
	}

	public int depositedTotal() {
		return deposited.values().stream().mapToInt(Integer::intValue).sum();
	}

	public void blockBroken() {
		blocksBroken++;
	}

	public void collected(String itemId, int count) {
		if (count > 0) {
			collected.merge(itemId, count, Integer::sum);
		}
	}

	public void deposited(String itemId, int count) {
		if (count > 0) {
			deposited.merge(itemId, count, Integer::sum);
		}
	}

	public void withdrawn(String itemId, int count) {
		if (count > 0) {
			withdrawn.merge(itemId, count, Integer::sum);
		}
	}

	/** Codec only. */
	public static TaskLedger restore(int blocksBroken, Map<String, Integer> collected, Map<String, Integer> deposited) {
		return restore(blocksBroken, collected, deposited, Map.of());
	}

	/** Codec only. */
	public static TaskLedger restore(int blocksBroken, Map<String, Integer> collected, Map<String, Integer> deposited, Map<String, Integer> withdrawn) {
		return restore(blocksBroken, collected, deposited, withdrawn, Map.of());
	}

	/** Codec only. */
	public static TaskLedger restore(int blocksBroken, Map<String, Integer> collected, Map<String, Integer> deposited, Map<String, Integer> withdrawn,
									 Map<String, Integer> smelted) {
		TaskLedger l = new TaskLedger();
		l.blocksBroken = blocksBroken;
		l.collected.putAll(collected);
		l.deposited.putAll(deposited);
		l.withdrawn.putAll(withdrawn);
		l.smelted.putAll(smelted);
		return l;
	}
}
