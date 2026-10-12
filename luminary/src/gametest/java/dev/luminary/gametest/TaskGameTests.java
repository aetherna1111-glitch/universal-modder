package dev.luminary.gametest;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.task.ActionValidator;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskState;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcEntities;
import dev.luminary.fabric.npc.NpcService;
import dev.luminary.fabric.state.NpcWorldState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The gather-wood task on a headless server, one NPC definition and one 13x6x13 arena per test (structure
 * {@code luminary-gametest:arena}: stone floor, air above) so tests sharing the server never touch each other.
 * Item accounting: logs in chest + logs held by the NPC + log item entities in the arena == blocks actually broken.
 */
public final class TaskGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	// The template's layer 0 is placed at relative y = 1 (the structure block sits at relative y = 0), so the stone floor
	// is at y = 1 and everything stands on y = 2.
	private static final int FLOOR_Y = 1;
	private static final BlockPos NPC_SPAWN = new BlockPos(2, FLOOR_Y + 1, 2);
	private static final BlockPos CHEST = new BlockPos(10, FLOOR_Y + 1, 2);
	private static final BlockPos LOG0 = new BlockPos(6, FLOOR_Y + 1, 6);

	// ------------------------------------------------------------------ fixtures

	private record Arena(LuminaryId npcId, LuminaryNpcEntity body, BlockPos chest, BlockPos log0, NpcService npc) {}

	private static Arena arena(TestContext ctx, String npcName, int logs, boolean authorize) {
		NpcService npc = LuminaryRuntime.get().orElseThrow().npc();
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/" + npcName);
		ctx.setBlockState(CHEST, Blocks.CHEST.getDefaultState());
		for (int i = 0; i < logs; i++) {
			ctx.setBlockState(LOG0.up(i), Blocks.OAK_LOG.getDefaultState());
		}
		Vec3d spawn = Vec3d.ofBottomCenter(ctx.getAbsolutePos(NPC_SPAWN));
		if (!npc.spawn(id, ctx.getWorld(), spawn).ok()) {
			throw new GameTestException("spawn failed for " + id);
		}
		BlockPos chest = ctx.getAbsolutePos(CHEST);
		if (authorize) {
			npc.authorizeContainer(id, chest.asLong());
		}
		return new Arena(id, npc.body(id).orElseThrow(), chest, ctx.getAbsolutePos(LOG0), npc);
	}

	private static NpcAction.GatherWood gather(Arena a, int maxLogs) {
		return new NpcAction.GatherWood(a.log0().asLong(), a.chest().asLong(), maxLogs);
	}

	private static Optional<NpcTask> task(Arena a) {
		return a.npc().roster().get(a.npcId()).flatMap(NpcRoster.NpcRecord::task);
	}

	private static int logsIn(TestContext ctx, BlockPos absChest) {
		BlockEntity be = ctx.getWorld().getBlockEntity(absChest);
		if (!(be instanceof Inventory inv)) {
			return -1;
		}
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isIn(ItemTags.LOGS)) {
				n += s.getCount();
			}
		}
		return n;
	}

	private static int logItemsInArena(TestContext ctx) {
		Vec3d min = Vec3d.of(ctx.getAbsolutePos(BlockPos.ORIGIN));
		Box box = new Box(min, min.add(13, 8, 13));
		return ctx.getWorld().getEntitiesByClass(ItemEntity.class, box, e -> e.getStack().isIn(ItemTags.LOGS))
				.stream().mapToInt(e -> e.getStack().getCount()).sum();
	}

	/** logs in chest + logs the NPC carries + log item entities still lying around. */
	private static int accountedLogs(TestContext ctx, Arena a) {
		return logsIn(ctx, a.chest()) + a.body().heldCount() + logItemsInArena(ctx);
	}

	/** Polls every 5 ticks until {@code done} or the budget is spent, then runs {@code then} (which must complete or throw). */
	private static void await(TestContext ctx, BooleanSupplier done, int budgetTicks, Runnable then) {
		if (done.getAsBoolean()) {
			then.run();
			return;
		}
		if (budgetTicks <= 0) {
			throw new GameTestException("condition not reached in time: " + diagnostics(ctx));
		}
		ctx.waitAndRun(5, () -> await(ctx, done, budgetTicks - 5, then));
	}

	private static String diagnostics(TestContext ctx) {
		StringBuilder sb = new StringBuilder();
		for (LuminaryNpcEntity e : ctx.getWorld().getEntitiesByClass(LuminaryNpcEntity.class,
				new Box(Vec3d.of(ctx.getAbsolutePos(BlockPos.ORIGIN)), Vec3d.of(ctx.getAbsolutePos(BlockPos.ORIGIN)).add(13, 8, 13)), x -> true)) {
			sb.append(e.npcId().map(Object::toString).orElse("?")).append(" at ").append(e.getBlockPos().toShortString())
					.append(" navIdle=").append(e.getNavigation().isIdle()).append(" onGround=").append(e.isOnGround());
			LuminaryRuntime.get().orElseThrow().npc().roster().get(e.npcId().orElseThrow()).flatMap(NpcRoster.NpcRecord::task)
					.ifPresent(t -> sb.append(" task=").append(t.state()).append(" phaseTicks=").append(t.phaseTicks()).append(" broken=").append(t.ledger().blocksBroken()));
			sb.append("; ");
		}
		sb.append("floor@(6,1,6)=").append(ctx.getWorld().getBlockState(ctx.getAbsolutePos(new BlockPos(6, FLOOR_Y, 6))).getBlock())
				.append(" origin=").append(ctx.getAbsolutePos(BlockPos.ORIGIN).toShortString())
				.append(" below-origin=").append(ctx.getWorld().getBlockState(ctx.getAbsolutePos(BlockPos.ORIGIN).down()).getBlock());
		return sb.toString();
	}

	private static void expect(boolean condition, String message) {
		if (!condition) {
			throw new GameTestException(message);
		}
	}

	// ------------------------------------------------------------------ tests

	@GameTest(templateName = ARENA, tickLimit = 1200)
	public void gatherDepositsRealDropsIntoTheAuthorizedContainer(TestContext ctx) {
		Arena a = arena(ctx, "gather_ok", 3, true);
		expect(logsIn(ctx, a.chest()) == 0 && logItemsInArena(ctx) == 0, "fixture must start empty");
		Result r = a.npc().assign(a.npcId(), gather(a, 3));
		expect(r.ok(), "assignment refused: " + r.reasonKey());
		expect(a.npc().assign(a.npcId(), gather(a, 3)).reasonKey().equals(ActionValidator.REASON_TASK_ACTIVE), "a second task must be refused while one is active");
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 1100, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.COMPLETED, "expected COMPLETED, got " + t.state() + " " + t.failureReason().orElse(""));
			expect(t.ledger().blocksBroken() == 3, "3 logs should have been broken, got " + t.ledger().blocksBroken());
			expect(logsIn(ctx, a.chest()) == 3, "chest must hold the 3 real drops, holds " + logsIn(ctx, a.chest()) + "; ledger collected=" + t.ledger().collected()
					+ " deposited=" + t.ledger().deposited() + " held=" + a.body().heldCount() + " itemEntitiesInArena=" + logItemsInArena(ctx) + "; " + diagnostics(ctx));
			expect(t.ledger().depositedTotal() == 3 && t.ledger().collectedTotal() == 3, "ledger must match: " + t.ledger().collected() + " / " + t.ledger().deposited());
			expect(a.body().heldCount() == 0 && logItemsInArena(ctx) == 0, "nothing may remain in hand or on the ground");
			expect(accountedLogs(ctx, a) == t.ledger().blocksBroken(), "every deposited log must come from a broken block");
			for (int i = 0; i < 3; i++) {
				expect(ctx.getWorld().getBlockState(a.log0().up(i)).isAir(), "log " + i + " must be gone from the world");
			}
			a.npc().despawn(a.npcId());
			ctx.complete();
		});
	}

	@GameTest(templateName = ARENA)
	public void nonWoodTargetIsRejectedWithoutMutation(TestContext ctx) {
		Arena a = arena(ctx, "gather_stone", 1, true);
		BlockPos stone = ctx.getAbsolutePos(new BlockPos(6, FLOOR_Y, 6));
		Result r = a.npc().assign(a.npcId(), new NpcAction.GatherWood(stone.asLong(), a.chest().asLong(), 1));
		expect(r.reasonKey().equals(ActionValidator.REASON_TARGET_NOT_LOG), "expected target_not_log, got " + r.reasonKey());
		expect(task(a).isEmpty(), "no task may be recorded for a rejected action");
		expect(ctx.getWorld().getBlockState(stone).isOf(Blocks.STONE), "the stone must be untouched: " + diagnostics(ctx));
		expect(logsIn(ctx, a.chest()) == 0 && a.body().heldCount() == 0, "no inventory may change");
		a.npc().despawn(a.npcId());
		ctx.complete();
	}

	@GameTest(templateName = ARENA)
	public void unauthorizedContainerIsRejected(TestContext ctx) {
		Arena a = arena(ctx, "gather_unauth", 1, false);
		Result r = a.npc().assign(a.npcId(), gather(a, 1));
		expect(r.reasonKey().equals(ActionValidator.REASON_CONTAINER_UNAUTHORIZED), "expected container_unauthorized, got " + r.reasonKey());
		a.npc().authorizeContainer(a.npcId(), a.chest().asLong());
		a.npc().revokeContainer(a.npcId(), a.chest().asLong());
		expect(a.npc().assign(a.npcId(), gather(a, 1)).reasonKey().equals(ActionValidator.REASON_CONTAINER_UNAUTHORIZED), "revoked authorization must count as none");
		// an authorized position that is not an inventory
		BlockPos floor = ctx.getAbsolutePos(new BlockPos(9, FLOOR_Y, 9));
		a.npc().authorizeContainer(a.npcId(), floor.asLong());
		expect(a.npc().assign(a.npcId(), new NpcAction.GatherWood(a.log0().asLong(), floor.asLong(), 1)).reasonKey().equals(ActionValidator.REASON_CONTAINER_NOT_INVENTORY),
				"a non-inventory destination must be rejected");
		expect(task(a).isEmpty() && ctx.getWorld().getBlockState(a.log0()).isOf(Blocks.OAK_LOG), "world must be untouched");
		a.npc().despawn(a.npcId());
		ctx.complete();
	}

	@GameTest(templateName = ARENA, tickLimit = 1800)
	public void fullContainerKeepsItemsWithTheNpcAndLaterDepositCatchesUp(TestContext ctx) {
		Arena a = arena(ctx, "gather_full", 2, true);
		expect(a.npc().assign(a.npcId(), gather(a, 1)).ok(), "assignment refused");
		Inventory chest = (Inventory) ctx.getWorld().getBlockEntity(a.chest());
		// the chest fills up while the NPC is already working (someone else used it): deposit must fail without losing anything
		await(ctx, () -> task(a).map(t -> t.ledger().blocksBroken() >= 1 || !t.isActive()).orElse(false), 800, () -> {
			expect(task(a).orElseThrow().isActive(), "task ended before the chest could be filled: " + task(a).orElseThrow().failureReason().orElse(""));
			for (int i = 0; i < chest.size(); i++) {
				chest.setStack(i, new ItemStack(Items.COBBLESTONE, 64));
			}
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 1000, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.FAILED && NpcTask.FAIL_CONTAINER_FULL.equals(t.failureReason().orElse("")), "expected FAILED container_full, got " + t.state() + " " + t.failureReason().orElse(""));
			expect(t.ledger().blocksBroken() == 1 && t.ledger().collectedTotal() == 1 && t.ledger().depositedTotal() == 0, "ledger: " + t.ledger().blocksBroken() + "/" + t.ledger().collected() + "/" + t.ledger().deposited());
			expect(a.body().heldCount() == 1, "the NPC must still carry the log, carries " + a.body().heldCount());
			int cobble = 0;
			for (int i = 0; i < chest.size(); i++) {
				cobble += chest.getStack(i).isOf(Items.COBBLESTONE) ? chest.getStack(i).getCount() : 0;
			}
			expect(cobble == chest.size() * 64 && logsIn(ctx, a.chest()) == 0, "the full chest must be unchanged");
			expect(accountedLogs(ctx, a) == 1, "one real log must be accounted for");
			// an administrator empties the chest; the next task deposits the carried log together with the new one
			chest.clear();
			expect(a.npc().assign(a.npcId(), new NpcAction.GatherWood(a.log0().up().asLong(), a.chest().asLong(), 1)).ok(), "second assignment refused");
			await(ctx, () -> task(a).map(t2 -> !t2.isActive()).orElse(false), 700, () -> {
				NpcTask t2 = task(a).orElseThrow();
				expect(t2.state() == TaskState.COMPLETED, "second task: " + t2.state() + " " + t2.failureReason().orElse(""));
				expect(logsIn(ctx, a.chest()) == 2 && a.body().heldCount() == 0 && logItemsInArena(ctx) == 0, "both logs (carried + new) must end in the chest, chest=" + logsIn(ctx, a.chest()));
				expect(t2.ledger().blocksBroken() == 1 && t2.ledger().depositedTotal() == 2, "second ledger must show 1 broken and 2 deposited (one carried over)");
				a.npc().despawn(a.npcId());
				ctx.complete();
			});
		});
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 900)
	public void targetChangedAfterValidationFailsSafely(TestContext ctx) {
		Arena a = arena(ctx, "gather_changed", 1, true);
		expect(a.npc().assign(a.npcId(), gather(a, 1)).ok(), "assignment refused");
		ctx.getWorld().setBlockState(a.log0(), Blocks.STONE.getDefaultState());
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 800, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.FAILED, "expected FAILED, got " + t.state());
			String reason = t.failureReason().orElse("");
			expect(reason.equals(NpcTask.FAIL_TARGET_CHANGED) || reason.equals(ActionValidator.REASON_TARGET_NOT_LOG), "unexpected reason " + reason);
			expect(t.ledger().blocksBroken() == 0 && logsIn(ctx, a.chest()) == 0 && a.body().heldCount() == 0, "nothing may be broken, carried or deposited");
			expect(ctx.getWorld().getBlockState(a.log0()).isOf(Blocks.STONE), "the replaced block must stay");
			a.npc().despawn(a.npcId());
			ctx.complete();
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 1200)
	public void extraTicksAndTerminalTasksNeverDuplicate(TestContext ctx) {
		Arena a = arena(ctx, "gather_repeat", 1, true);
		expect(a.npc().assign(a.npcId(), gather(a, 1)).ok(), "assignment refused");
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 1000, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.COMPLETED && logsIn(ctx, a.chest()) == 1, "setup: expected one deposited log, got " + t.state() + "/" + logsIn(ctx, a.chest()));
			for (int i = 0; i < 100; i++) {
				a.npc().tickTask(a.body());
				a.npc().executor().tick(a.body(), a.npc().roster().get(a.npcId()).orElseThrow());
			}
			expect(logsIn(ctx, a.chest()) == 1 && t.ledger().depositedTotal() == 1 && t.state() == TaskState.COMPLETED, "extra ticks on a terminal task must change nothing");
			// re-submitting the same action: the log is gone, so validation refuses; chest unchanged
			Result again = a.npc().assign(a.npcId(), gather(a, 1));
			expect(again.reasonKey().equals(ActionValidator.REASON_TARGET_NOT_LOG), "re-assignment must be refused: " + again.reasonKey());
			expect(logsIn(ctx, a.chest()) == 1 && accountedLogs(ctx, a) == 1, "no duplicate reward");
			a.npc().despawn(a.npcId());
			ctx.complete();
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 1800)
	public void reloadMidTaskPreservesStateAndAccounting(TestContext ctx) {
		Arena a = arena(ctx, "gather_reload", 2, true);
		expect(a.npc().assign(a.npcId(), gather(a, 2)).ok(), "assignment refused");
		// wait until the first block is really broken, then simulate a chunk unload/reload of the body
		await(ctx, () -> task(a).map(t -> t.ledger().blocksBroken() >= 1 || !t.isActive()).orElse(false), 800, () -> {
			NpcTask before = task(a).orElseThrow();
			expect(before.isActive(), "task ended before the reload point: " + before.state() + " " + before.failureReason().orElse(""));
			int brokenAtReload = before.ledger().blocksBroken();
			TaskState stateAtReload = before.state();
			// roster persistence round trip must carry the in-flight task
			NpcWorldState live = NpcWorldState.get(ctx.getWorld().getServer());
			NpcWorldState copy = NpcWorldState.fromNbt(live.writeNbt(new NbtCompound()));
			NpcTask persisted = copy.roster().get(a.npcId()).flatMap(NpcRoster.NpcRecord::task).orElseThrow(() -> new GameTestException("task not persisted"));
			expect(persisted.id().equals(before.id()) && persisted.state() == stateAtReload && persisted.ledger().blocksBroken() == brokenAtReload, "persisted task must mirror the live one");
			// entity unload → load with the same NBT (what the chunk does)
			LuminaryNpcEntity old = a.body();
			NbtCompound nbt = new NbtCompound();
			old.writeNbt(nbt);
			int heldAtReload = old.heldCount();
			old.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
			ctx.waitAndRun(2, () -> {
				LuminaryNpcEntity reloaded = NpcEntities.NPC.create(ctx.getWorld());
				reloaded.readNbt(nbt);
				expect(ctx.getWorld().spawnEntity(reloaded), "reloaded body could not be added");
				expect(a.npc().body(a.npcId()).map(b -> b == reloaded).orElse(false), "roster must accept the reloaded body (same uuid)");
				expect(reloaded.heldCount() == heldAtReload, "carried items must survive the reload");
				Arena after = new Arena(a.npcId(), reloaded, a.chest(), a.log0(), a.npc());
				await(ctx, () -> task(after).map(t -> !t.isActive()).orElse(false), 800, () -> {
					NpcTask t = task(after).orElseThrow();
					expect(t.id().equals(before.id()), "the same task must have continued, not a new one");
					expect(t.state() == TaskState.COMPLETED, "expected COMPLETED after reload, got " + t.state() + " " + t.failureReason().orElse(""));
					expect(t.ledger().blocksBroken() == 2 && logsIn(ctx, after.chest()) == 2, "exactly the 2 real drops must be in the chest, chest=" + logsIn(ctx, after.chest()) + " broken=" + t.ledger().blocksBroken());
					expect(accountedLogs(ctx, after) == 2 && reloaded.heldCount() == 0, "accounting must close: " + accountedLogs(ctx, after));
					a.npc().despawn(a.npcId());
					ctx.complete();
				});
			});
		});
	}

	@GameTest(templateName = ARENA)
	public void invalidArgumentsAndUnreachablePositionsAreRejected(TestContext ctx) {
		Arena a = arena(ctx, "gather_args", 1, true);
		expect(a.npc().assign(a.npcId(), gather(a, 0)).reasonKey().equals(ActionValidator.REASON_BAD_ARGUMENTS), "maxLogs 0 must be rejected");
		expect(a.npc().assign(a.npcId(), gather(a, 999)).reasonKey().equals(ActionValidator.REASON_BAD_ARGUMENTS), "maxLogs 999 must be rejected");
		expect(a.npc().assign(a.npcId(), new NpcAction.GatherWood(a.log0().asLong(), a.log0().asLong(), 1)).reasonKey().equals(ActionValidator.REASON_BAD_ARGUMENTS), "target == container must be rejected");
		BlockPos far = ctx.getAbsolutePos(new BlockPos(40, FLOOR_Y + 1, 2));
		expect(a.npc().assign(a.npcId(), new NpcAction.GatherWood(far.asLong(), a.chest().asLong(), 1)).reasonKey().equals(ActionValidator.REASON_OUT_OF_RANGE), "40 blocks away must be out of range");
		BlockPos unloaded = ctx.getAbsolutePos(new BlockPos(100000, FLOOR_Y + 1, 2));
		expect(a.npc().assign(a.npcId(), new NpcAction.GatherWood(unloaded.asLong(), a.chest().asLong(), 1)).reasonKey().equals(ActionValidator.REASON_NOT_LOADED), "an unloaded chunk must be rejected");
		expect(a.npc().assign(LuminaryId.parse("luminary-gametest:npc/ghost"), gather(a, 1)).reasonKey().equals(NpcService.REASON_UNKNOWN_NPC), "unknown NPC must be rejected");
		expect(task(a).isEmpty() && logsIn(ctx, a.chest()) == 0, "nothing may be recorded or deposited");
		a.npc().despawn(a.npcId());
		ctx.complete();
	}
}
