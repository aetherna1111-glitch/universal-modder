package dev.luminary.fabric.npc.task;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.task.ActionContext;
import dev.luminary.core.npc.task.ActionValidator;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskRules;
import dev.luminary.core.npc.task.TaskState;
import dev.luminary.fabric.Luminary;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs the active task of one NPC body, one step per server tick, through the real world APIs:
 * {@code ServerWorld.breakBlock} (loot tables decide the drops), {@code ItemEntity} absorption into the NPC's own
 * inventory, and {@code Inventory} insertion into the authorized container. The executor cannot bypass validation:
 * it only runs tasks the validators accepted, re-checks the target and container every step, and every ledger
 * increment is tied to a world mutation that just happened.
 */
public final class TaskExecutor {
	private static final double REACH = 3.0;
	private static final double REACH_SQ = REACH * REACH;
	private static final double COLLECT_RADIUS = 6.0;
	/** Same reach a vanilla mob uses to pick up loot: its bounding box grown by one block horizontally. */
	private static final double PICKUP_EXPAND_XZ = 1.0;
	private static final double PICKUP_EXPAND_Y = 0.5;
	/** Ticks with no visible drop before the collect phase ends (drops of the last break settle within a few ticks). */
	private static final int EMPTY_GRACE_TICKS = 10;

	private final TaskRules rules;
	private final List<ActionValidator> validators;
	private final Runnable onChange;

	public TaskExecutor(TaskRules rules, Runnable onChange) {
		this.rules = rules;
		this.validators = ActionValidator.standard(rules);
		this.onChange = onChange;
	}

	public TaskRules rules() {
		return rules;
	}

	public List<ActionValidator> validators() {
		return validators;
	}

	/** One step. Returns true when the task changed (caller persists). */
	public boolean tick(LuminaryNpcEntity npc, NpcRoster.NpcRecord record) {
		Optional<NpcTask> maybe = record.task();
		if (maybe.isEmpty() || !maybe.get().isActive() || !(npc.getWorld() instanceof ServerWorld world)) {
			return false;
		}
		NpcTask task = maybe.get();
		if (task.action() instanceof NpcAction.GatherWood action) {
			switch (task.state()) {
				case VALIDATED -> stepValidated(npc, world, record, task, action.targetPos(), TaskState.MOVING_TO_TARGET);
				case MOVING_TO_TARGET -> stepMoveTo(npc, world, task, BlockPos.fromLong(task.currentTarget()), TaskState.BREAKING, task.currentTarget(), Check.LOG);
				case BREAKING -> stepBreaking(npc, world, task, action);
				case COLLECTING -> stepCollecting(npc, world, task, action);
				case MOVING_TO_CONTAINER -> stepMoveTo(npc, world, task, BlockPos.fromLong(action.containerPos()), TaskState.DEPOSITING, action.containerPos(), Check.CONTAINER);
				case DEPOSITING -> stepDepositing(npc, world, record, task, action.containerPos());
				default -> { }
			}
		} else if (task.action() instanceof NpcAction.MoveTo action) {
			switch (task.state()) {
				case VALIDATED -> stepValidated(npc, world, record, task, action.targetPos(), TaskState.MOVING_TO_TARGET);
				case MOVING_TO_TARGET -> {
					stepMoveTo(npc, world, task, BlockPos.fromLong(action.targetPos()), TaskState.MOVING_TO_TARGET, action.targetPos(), Check.NONE);
					if (task.isActive() && npc.squaredDistanceTo(Vec3d.ofCenter(BlockPos.fromLong(action.targetPos()))) <= REACH_SQ) {
						task.complete();
					}
				}
				default -> { }
			}
		} else if (task.action() instanceof NpcAction.Deposit action) {
			switch (task.state()) {
				case VALIDATED -> stepValidated(npc, world, record, task, action.containerPos(), TaskState.MOVING_TO_CONTAINER);
				case MOVING_TO_CONTAINER -> stepMoveTo(npc, world, task, BlockPos.fromLong(action.containerPos()), TaskState.DEPOSITING, action.containerPos(), Check.CONTAINER);
				case DEPOSITING -> stepDepositing(npc, world, record, task, action.containerPos());
				default -> { }
			}
		} else if (task.action() instanceof NpcAction.Withdraw action) {
			switch (task.state()) {
				case VALIDATED -> stepValidated(npc, world, record, task, action.containerPos(), TaskState.MOVING_TO_CONTAINER);
				case MOVING_TO_CONTAINER -> stepMoveTo(npc, world, task, BlockPos.fromLong(action.containerPos()), TaskState.WITHDRAWING, action.containerPos(), Check.CONTAINER);
				case WITHDRAWING -> stepWithdrawing(npc, world, record, task, action);
				default -> { }
			}
		} else {
			task.fail(ActionValidator.REASON_UNSUPPORTED);
		}
		return changed();
	}

	private enum Check { NONE, LOG, CONTAINER }

	private boolean changed() {
		onChange.run();
		return true;
	}

	// ------------------------------------------------------------------ phases

	/** Fresh or resumed-before-start: re-validate against the current world, then walk. */
	private void stepValidated(LuminaryNpcEntity npc, ServerWorld world, NpcRoster.NpcRecord record, NpcTask task, long firstTarget, TaskState next) {
		ActionContext ctx = new WorldActionContext(world, npc, record);
		var result = ActionValidator.validateAll(validators, task.action(), ctx);
		if (!result.ok()) {
			task.fail(result.reasonKey());
			return;
		}
		task.setCurrentTarget(firstTarget);
		task.enter(next);
	}

	private void stepMoveTo(LuminaryNpcEntity npc, ServerWorld world, NpcTask task, BlockPos goal, TaskState next, long checkPos, Check check) {
		// the thing we walk to must still be what we validated
		ActionContext.BlockKind kind = WorldActionContext.kindAt(world, BlockPos.fromLong(checkPos));
		if (check == Check.LOG && kind != ActionContext.BlockKind.LOG) {
			task.fail(NpcTask.FAIL_TARGET_CHANGED);
			return;
		}
		if (check == Check.CONTAINER && kind != ActionContext.BlockKind.CONTAINER) {
			task.fail(NpcTask.FAIL_CONTAINER_INVALID);
			return;
		}
		if (npc.squaredDistanceTo(Vec3d.ofCenter(goal)) <= REACH_SQ) {
			npc.getNavigation().stop();
			if (next != task.state()) {
				task.enter(next);
			}
			return;
		}
		if (task.tickPhase() % 20 == 1) {
			npc.getNavigation().startMovingTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
		}
		if (task.phaseTicks() > rules.moveTimeoutTicks()) {
			npc.getNavigation().stop();
			task.fail(NpcTask.FAIL_UNREACHABLE);
		}
	}

	private void stepBreaking(LuminaryNpcEntity npc, ServerWorld world, NpcTask task, NpcAction.GatherWood action) {
		BlockPos target = BlockPos.fromLong(task.currentTarget());
		if (WorldActionContext.kindAt(world, target) != ActionContext.BlockKind.LOG) {
			// the block changed under us: stop breaking; collect whatever was already dropped
			if (task.ledger().blocksBroken() == 0) {
				task.fail(NpcTask.FAIL_TARGET_CHANGED);
			} else {
				task.enter(TaskState.COLLECTING);
			}
			return;
		}
		if (npc.squaredDistanceTo(Vec3d.ofCenter(target)) > (REACH + 2.0) * (REACH + 2.0)) {
			// trunk grew out of reach (tall tree): what we have is enough
			task.enter(TaskState.COLLECTING);
			return;
		}
		npc.getLookControl().lookAt(Vec3d.ofCenter(target));
		if (task.tickPhase() < rules.breakTicksPerLog()) {
			return;
		}
		// Real break: loot tables produce the drops as ItemEntities; nothing is granted directly.
		if (world.breakBlock(target, true, npc)) {
			task.ledger().blockBroken();
		}
		BlockPos up = target.up();
		if (task.ledger().blocksBroken() < action.maxLogs() && WorldActionContext.kindAt(world, up) == ActionContext.BlockKind.LOG) {
			task.setCurrentTarget(up.asLong());
			task.enter(TaskState.MOVING_TO_TARGET);   // re-enter resets phase ticks; reach check passes immediately
			return;
		}
		task.enter(TaskState.COLLECTING);
	}

	private void stepCollecting(LuminaryNpcEntity npc, ServerWorld world, NpcTask task, NpcAction.GatherWood action) {
		Vec3d base = Vec3d.ofCenter(BlockPos.fromLong(action.targetPos()));
		List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, Box.of(base, COLLECT_RADIUS * 2, COLLECT_RADIUS * 2, COLLECT_RADIUS * 2),
				e -> e.isAlive() && !e.getStack().isEmpty() && e.getStack().isIn(ItemTags.LOGS));
		task.tickPhase();
		if (drops.isEmpty()) {
			if (task.tickIdle() < EMPTY_GRACE_TICKS && task.phaseTicks() <= rules.collectTimeoutTicks()) {
				return;
			}
		} else {
			task.resetIdle();
		}
		if (drops.isEmpty() || task.phaseTicks() > rules.collectTimeoutTicks()) {
			npc.getNavigation().stop();
			if (task.ledger().collectedTotal() == 0) {
				task.fail(NpcTask.FAIL_NOTHING_COLLECTED);
			} else {
				task.enter(TaskState.MOVING_TO_CONTAINER);   // drops still on the ground stay there: ledger shows collected < broken
			}
			return;
		}
		ItemEntity nearest = drops.stream().min(Comparator.comparingDouble(npc::squaredDistanceTo)).orElseThrow();
		if (npc.getBoundingBox().expand(PICKUP_EXPAND_XZ, PICKUP_EXPAND_Y, PICKUP_EXPAND_XZ).intersects(nearest.getBoundingBox())) {
			if (nearest.cannotPickup()) {
				return;   // vanilla pickup delay after a drop: wait, do not skip the item
			}
			absorb(npc, nearest, task);
			return;
		}
		if (task.phaseTicks() % 10 == 1 || npc.getNavigation().isIdle()) {
			// A path ends in the item's block, which can still be out of reach when the item lies at the far edge of
			// that block; steer straight at it with the move control, which needs no path for a one-block hop.
			if (!npc.getNavigation().startMovingTo(nearest, 1.0) || npc.getNavigation().isIdle()) {
				npc.getMoveControl().moveTo(nearest.getX(), nearest.getY(), nearest.getZ(), 1.0);
			}
		}
	}

	/** Moves as much of the item entity's stack into the NPC inventory as fits; the entity keeps the remainder. */
	private static void absorb(LuminaryNpcEntity npc, ItemEntity item, NpcTask task) {
		ItemStack stack = item.getStack();
		int before = stack.getCount();
		ItemStack remainder = npc.heldItems().addStack(stack.copy());
		int moved = before - remainder.getCount();
		if (moved <= 0) {
			return;   // NPC inventory full: leave the drop in the world, accounted for by the test's world count
		}
		task.ledger().collected(Registries.ITEM.getId(stack.getItem()).toString(), moved);
		if (remainder.isEmpty()) {
			item.discard();
		} else {
			item.setStack(remainder);
		}
	}

	private void stepDepositing(LuminaryNpcEntity npc, ServerWorld world, NpcRoster.NpcRecord record, NpcTask task, long containerPos) {
		BlockPos pos = BlockPos.fromLong(containerPos);
		if (!record.authorizedContainers().contains(containerPos)) {
			task.fail(ActionValidator.REASON_CONTAINER_UNAUTHORIZED);
			return;
		}
		BlockEntity be = world.getBlockEntity(pos);
		if (!(be instanceof Inventory inv)) {
			task.fail(NpcTask.FAIL_CONTAINER_INVALID);
			return;
		}
		SimpleInventory held = npc.heldItems();
		boolean anyLeft = false;
		for (int slot = 0; slot < held.size(); slot++) {
			ItemStack stack = held.getStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			int moved = insert(inv, stack);
			if (moved > 0) {
				task.ledger().deposited(Registries.ITEM.getId(stack.getItem()).toString(), moved);
			}
			if (!stack.isEmpty()) {
				anyLeft = true;
			}
		}
		inv.markDirty();
		held.markDirty();
		if (anyLeft) {
			task.fail(NpcTask.FAIL_CONTAINER_FULL);   // the remainder stays in the NPC inventory (persisted), never dropped
		} else {
			task.complete();
		}
	}

	/** Takes up to {@code count} of the item out of the container into the NPC inventory; what does not fit stays in the chest. */
	private void stepWithdrawing(LuminaryNpcEntity npc, ServerWorld world, NpcRoster.NpcRecord record, NpcTask task, NpcAction.Withdraw action) {
		BlockPos pos = BlockPos.fromLong(action.containerPos());
		if (!record.authorizedContainers().contains(action.containerPos())) {
			task.fail(ActionValidator.REASON_CONTAINER_UNAUTHORIZED);
			return;
		}
		BlockEntity be = world.getBlockEntity(pos);
		if (!(be instanceof Inventory inv)) {
			task.fail(NpcTask.FAIL_CONTAINER_INVALID);
			return;
		}
		Identifier itemId = Identifier.tryParse(action.itemId());
		if (itemId == null || !Registries.ITEM.containsId(itemId)) {
			task.fail(ActionValidator.REASON_UNKNOWN_ITEM);
			return;
		}
		Item item = Registries.ITEM.get(itemId);
		int wanted = action.count() - task.ledger().withdrawnTotal();
		SimpleInventory held = npc.heldItems();
		for (int i = 0; i < inv.size() && wanted > 0; i++) {
			ItemStack slot = inv.getStack(i);
			if (slot.isEmpty() || !slot.isOf(item)) {
				continue;
			}
			ItemStack taking = slot.copy();
			taking.setCount(Math.min(wanted, slot.getCount()));
			ItemStack remainder = held.addStack(taking);
			int moved = taking.getCount() - remainder.getCount();
			if (moved <= 0) {
				break;   // hands full: stop, keep the chest intact
			}
			slot.decrement(moved);
			inv.setStack(i, slot);
			task.ledger().withdrawn(action.itemId(), moved);
			wanted -= moved;
		}
		inv.markDirty();
		held.markDirty();
		if (task.ledger().withdrawnTotal() == 0) {
			task.fail(NpcTask.FAIL_CONTAINER_LACKS_ITEM);
		} else {
			task.complete();   // fewer than asked is still a completed task; the ledger says how many
		}
	}

	/** Inserts into existing matching stacks first, then empty valid slots. Returns the count moved; shrinks {@code stack}. */
	private static int insert(Inventory inv, ItemStack stack) {
		int moved = 0;
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
				ItemStack slot = inv.getStack(i);
				if (pass == 0) {
					if (!slot.isEmpty() && ItemStack.canCombine(slot, stack) && slot.getCount() < Math.min(slot.getMaxCount(), inv.getMaxCountPerStack())) {
						int room = Math.min(slot.getMaxCount(), inv.getMaxCountPerStack()) - slot.getCount();
						int n = Math.min(room, stack.getCount());
						slot.increment(n);
						stack.decrement(n);
						moved += n;
					}
				} else if (slot.isEmpty() && inv.isValid(i, stack)) {
					int n = Math.min(stack.getCount(), Math.min(stack.getMaxCount(), inv.getMaxCountPerStack()));
					ItemStack placed = stack.copy();
					placed.setCount(n);
					inv.setStack(i, placed);
					stack.decrement(n);
					moved += n;
				}
			}
		}
		return moved;
	}

	/** Convenience for commands/tests. */
	public static LuminaryId idOf(LuminaryNpcEntity npc) {
		return npc.npcId().orElseThrow();
	}

	static void log(String msg, Object... args) {
		Luminary.LOGGER.debug("[luminary] " + msg, args);
	}
}
