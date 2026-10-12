package dev.luminary.core.npc.task;

import dev.luminary.core.catalog.Result;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorsTest {
	/** A programmable world for the validators. */
	static final class FakeContext implements ActionContext {
		final Map<Long, BlockKind> kinds = new HashMap<>();
		final Map<Long, Double> distances = new HashMap<>();
		final Set<Long> authorized = new HashSet<>();
		final Map<Long, Integer> freeSlots = new HashMap<>();
		final Set<Long> unloaded = new HashSet<>();
		boolean permitted = true;

		@Override public boolean isLoaded(long pos) { return !unloaded.contains(pos); }
		@Override public BlockKind kindAt(long pos) { return kinds.getOrDefault(pos, BlockKind.AIR); }
		@Override public double distanceSq(long pos) { return distances.getOrDefault(pos, 4.0); }
		@Override public boolean containerAuthorized(long pos) { return authorized.contains(pos); }
		@Override public int containerFreeSlots(long pos) { return freeSlots.getOrDefault(pos, -1); }
		@Override public boolean permitted(NpcAction action) { return permitted; }
	}

	static final long LOG = 1L, CHEST = 2L, STONE = 3L, FAR = 4L;
	final List<ActionValidator> chain = ActionValidator.standard(TaskRules.DEFAULTS);

	FakeContext goodWorld() {
		FakeContext c = new FakeContext();
		c.kinds.put(LOG, ActionContext.BlockKind.LOG);
		c.kinds.put(CHEST, ActionContext.BlockKind.CONTAINER);
		c.kinds.put(STONE, ActionContext.BlockKind.OTHER);
		c.authorized.add(CHEST);
		c.freeSlots.put(CHEST, 27);
		c.distances.put(FAR, 40.0 * 40.0);
		c.kinds.put(FAR, ActionContext.BlockKind.LOG);
		return c;
	}

	Result run(NpcAction a, ActionContext c) {
		return ActionValidator.validateAll(chain, a, c);
	}

	@Test
	void validTaskPasses() {
		assertTrue(run(new NpcAction.GatherWood(LOG, CHEST, 3), goodWorld()).ok());
	}

	@Test
	void argumentsAreChecked() {
		assertEquals(ActionValidator.REASON_BAD_ARGUMENTS, run(new NpcAction.GatherWood(LOG, CHEST, 0), goodWorld()).reasonKey());
		assertEquals(ActionValidator.REASON_BAD_ARGUMENTS, run(new NpcAction.GatherWood(LOG, CHEST, 17), goodWorld()).reasonKey());
		assertEquals(ActionValidator.REASON_BAD_ARGUMENTS, run(new NpcAction.GatherWood(LOG, LOG, 1), goodWorld()).reasonKey());
	}

	@Test
	void worldRulesAreChecked() {
		assertEquals(ActionValidator.REASON_OUT_OF_RANGE, run(new NpcAction.GatherWood(FAR, CHEST, 1), goodWorld()).reasonKey());
		assertEquals(ActionValidator.REASON_TARGET_NOT_LOG, run(new NpcAction.GatherWood(STONE, CHEST, 1), goodWorld()).reasonKey());
		assertEquals(ActionValidator.REASON_TARGET_NOT_LOG, run(new NpcAction.GatherWood(99L, CHEST, 1), goodWorld()).reasonKey());
		FakeContext c = goodWorld();
		c.unloaded.add(LOG);
		assertEquals(ActionValidator.REASON_NOT_LOADED, run(new NpcAction.GatherWood(LOG, CHEST, 1), c).reasonKey());
		c = goodWorld();
		c.permitted = false;
		assertEquals(ActionValidator.REASON_NOT_PERMITTED, run(new NpcAction.GatherWood(LOG, CHEST, 1), c).reasonKey());
	}

	@Test
	void containerRulesAreChecked() {
		FakeContext c = goodWorld();
		c.authorized.clear();
		assertEquals(ActionValidator.REASON_CONTAINER_UNAUTHORIZED, run(new NpcAction.GatherWood(LOG, CHEST, 1), c).reasonKey());
		c = goodWorld();
		c.freeSlots.put(CHEST, 0);
		assertEquals(ActionValidator.REASON_CONTAINER_FULL, run(new NpcAction.GatherWood(LOG, CHEST, 1), c).reasonKey());
		c = goodWorld();
		c.authorized.add(STONE);
		assertEquals(ActionValidator.REASON_CONTAINER_NOT_INVENTORY, run(new NpcAction.GatherWood(LOG, STONE, 1), c).reasonKey());
	}
}
