package dev.luminary.gametest;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.autonomy.AutonomyState;
import dev.luminary.core.npc.autonomy.StandingOrder;
import dev.luminary.core.npc.intent.KeywordIntentPlanner;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskRules;
import dev.luminary.core.npc.task.TaskState;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Slice 9 on the headless server: the new physical actions (withdraw, deposit, move) through the real inventories,
 * and the whole chain words → planner → validation → execution → memory with the deterministic keyword planner
 * (no model configured). Same arena as {@link TaskGameTests}.
 */
public final class IntentGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	private static final int FLOOR_Y = 1;
	private static final BlockPos NPC_SPAWN = new BlockPos(2, FLOOR_Y + 1, 2);
	private static final BlockPos CHEST = new BlockPos(10, FLOOR_Y + 1, 2);
	private static final BlockPos LOG0 = new BlockPos(6, FLOOR_Y + 1, 6);

	private record Arena(LuminaryId npcId, LuminaryNpcEntity body, BlockPos chest, BlockPos log0, NpcService npc) {}

	private static Arena arena(TestContext ctx, String npcName, int logs) {
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
		npc.authorizeContainer(id, chest.asLong());
		return new Arena(id, npc.body(id).orElseThrow(), chest, ctx.getAbsolutePos(LOG0), npc);
	}

	private static Inventory chest(TestContext ctx, BlockPos abs) {
		BlockEntity be = ctx.getWorld().getBlockEntity(abs);
		if (!(be instanceof Inventory inv)) {
			throw new GameTestException("no inventory at " + abs);
		}
		return inv;
	}

	private static int count(Inventory inv, net.minecraft.item.Item item) {
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	private static int logsIn(TestContext ctx, BlockPos abs) {
		return count(chest(ctx, abs), Items.OAK_LOG);
	}

	private static Optional<NpcTask> task(Arena a) {
		return a.npc().roster().get(a.npcId()).flatMap(NpcRoster.NpcRecord::task);
	}

	private static void await(TestContext ctx, BooleanSupplier done, int budgetTicks, Runnable then) {
		if (done.getAsBoolean()) {
			then.run();
			return;
		}
		if (budgetTicks <= 0) {
			throw new GameTestException("condition not reached in time");
		}
		ctx.waitAndRun(5, () -> await(ctx, done, budgetTicks - 5, then));
	}

	/** Moves and turns a mock player; a real client syncs the head yaw itself, the mock needs it set for the gaze vector. */
	private static void face(PlayerEntity player, Vec3d at, float yaw, float pitch) {
		player.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
		player.setHeadYaw(yaw);
		player.setBodyYaw(yaw);
		player.prevHeadYaw = yaw;
		player.prevPitch = pitch;
		player.prevYaw = yaw;
	}

	private static void expect(boolean condition, String message) {
		if (!condition) {
			throw new GameTestException(message);
		}
	}

	// ------------------------------------------------------------------ tests

	@GameTest(templateName = ARENA, tickLimit = 1200)
	public void withdrawThenDepositMoveRealItemsBothWays(TestContext ctx) {
		Arena a = arena(ctx, "act_withdraw", 0);
		Inventory inv = chest(ctx, a.chest());
		inv.setStack(0, new ItemStack(Items.OAK_LOG, 12));
		inv.setStack(1, new ItemStack(Items.COBBLESTONE, 7));
		inv.markDirty();
		// refusals first: item the chest lacks, unknown item, nothing to deposit
		expect(a.npc().assign(a.npcId(), new NpcAction.Withdraw(a.chest().asLong(), "minecraft:diamond", 1)).reasonKey().equals("luminary.task.container_lacks_item"), "lacking item must be refused");
		expect(a.npc().assign(a.npcId(), new NpcAction.Withdraw(a.chest().asLong(), "minecraft:no_such", 1)).reasonKey().equals("luminary.task.unknown_item"), "unknown item must be refused");
		expect(a.npc().assign(a.npcId(), new NpcAction.Deposit(a.chest().asLong())).reasonKey().equals("luminary.task.nothing_to_deposit"), "empty-handed deposit must be refused");
		Result r = a.npc().assign(a.npcId(), new NpcAction.Withdraw(a.chest().asLong(), "minecraft:oak_log", 5));
		expect(r.ok(), "withdraw refused: " + r.reasonKey());
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 600, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.COMPLETED, "withdraw should complete, got " + t.state() + " " + t.failureReason().orElse(""));
			expect(t.ledger().withdrawnTotal() == 5, "ledger withdrawn=" + t.ledger().withdrawn());
			expect(a.body().heldCount() == 5, "NPC must carry 5, carries " + a.body().heldCount());
			expect(logsIn(ctx, a.chest()) == 7 && count(chest(ctx, a.chest()), Items.COBBLESTONE) == 7, "chest must have 7 logs and untouched cobblestone");
			Result d = a.npc().assign(a.npcId(), new NpcAction.Deposit(a.chest().asLong()));
			expect(d.ok(), "deposit refused: " + d.reasonKey());
			await(ctx, () -> task(a).map(x -> !x.isActive()).orElse(false), 500, () -> {
				NpcTask t2 = task(a).orElseThrow();
				expect(t2.state() == TaskState.COMPLETED, "deposit should complete, got " + t2.state() + " " + t2.failureReason().orElse(""));
				expect(t2.ledger().depositedTotal() == 5 && a.body().heldCount() == 0, "all 5 must be back; held=" + a.body().heldCount());
				expect(logsIn(ctx, a.chest()) == 12, "chest must hold 12 logs again, holds " + logsIn(ctx, a.chest()));
				a.npc().despawn(a.npcId());
				ctx.complete();
			});
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 800)
	public void moveToWalksWithinReachAndCompletes(TestContext ctx) {
		Arena a = arena(ctx, "act_move", 0);
		BlockPos goal = ctx.getAbsolutePos(new BlockPos(10, FLOOR_Y + 1, 10));
		expect(a.body().squaredDistanceTo(Vec3d.ofCenter(goal)) > 36, "fixture: NPC must start far from the goal");
		Result r = a.npc().assign(a.npcId(), new NpcAction.MoveTo(goal.asLong()));
		expect(r.ok(), "move refused: " + r.reasonKey());
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 700, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.COMPLETED, "move should complete, got " + t.state() + " " + t.failureReason().orElse(""));
			expect(a.body().squaredDistanceTo(Vec3d.ofCenter(goal)) <= 9.0 + 1e-6, "NPC must stand within reach of the goal");
			a.npc().despawn(a.npcId());
			ctx.complete();
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 1400)
	public void spokenRequestBecomesAValidatedTaskExecutedAndRemembered(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		rt.setIntentPlanner(new KeywordIntentPlanner(TaskRules.DEFAULTS));
		Arena a = arena(ctx, "intent_gather", 2);
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		Vec3d near = Vec3d.ofBottomCenter(ctx.getAbsolutePos(NPC_SPAWN.add(1, 0, 0)));
		player.refreshPositionAndAngles(near.x, near.y, near.z, 0, 0);
		NpcService.ChatOutcome out = a.npc().onPlayerChat(player, "Hey, could you chop 2 logs for me?").join();
		expect(out.intent().isPresent(), "the keyword planner must see work in the request");
		expect(out.intent().get().action().orElse(null) instanceof NpcAction.GatherWood g && g.maxLogs() == 2 && g.targetPos() == a.log0().asLong() && g.containerPos() == a.chest().asLong(),
				"the planner must choose the arena log and the authorized chest: " + out.intent().get().action());
		expect(out.assignment().map(Result::ok).orElse(false), "assignment must succeed: " + out.assignment().map(Result::reasonKey).orElse("none"));
		expect(out.turn().isEmpty(), "a work request is not a dialogue turn");
		MemoryStore memory = a.npc().dialogue().memory(a.npcId());
		expect(memory.forPlayer(player.getUuid()).stream().anyMatch(e -> e.kind() == MemoryKind.PROMISE && e.text().contains("gather 2 logs")), "the promise must be remembered: " + memory.all());
		expect(task(a).map(t -> t.requestedBy().equals(Optional.of(player.getUuid()))).orElse(false), "the task must record who asked");
		// a second request while busy is declined politely and assigns nothing
		NpcService.ChatOutcome busy = a.npc().onPlayerChat(player, "and after that chop some more wood").join();
		expect(busy.intent().isPresent() && busy.intent().get().action().isEmpty() && busy.assignment().isEmpty(), "busy NPC must decline: " + busy);
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 1300, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.COMPLETED, "expected COMPLETED, got " + t.state() + " " + t.failureReason().orElse(""));
			expect(t.ledger().blocksBroken() == 2 && logsIn(ctx, a.chest()) == 2, "2 real logs must be in the chest, has " + logsIn(ctx, a.chest()));
			List<MemoryEntry> live = memory.forPlayer(player.getUuid());
			expect(live.stream().noneMatch(e -> e.kind() == MemoryKind.PROMISE), "the promise must be superseded once kept: " + live);
			expect(live.stream().anyMatch(e -> e.kind() == MemoryKind.SIGNIFICANT_EVENT && e.text().startsWith("Completed") && e.text().contains("deposited 2")), "the outcome must be remembered: " + live);
			a.npc().despawn(a.npcId());
			ctx.complete();
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void smallTalkStillReachesDialogueAndNonePlannerNeverPlans(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		NpcService npc = rt.npc();
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/intent_talk");
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		expect(npc.spawn(id, ctx.getWorld(), pos).ok(), "spawn failed");
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		player.refreshPositionAndAngles(pos.x + 2, pos.y, pos.z, 0, 0);
		rt.setIntentPlanner(new KeywordIntentPlanner(TaskRules.DEFAULTS));
		NpcService.ChatOutcome chat = npc.onPlayerChat(player, "Good evening! How are you?").join();
		expect(chat.intent().isEmpty() && chat.turn().isPresent() && chat.turn().get().fromProvider(), "small talk must become a dialogue turn: " + chat);
		// a work request with no trees or containers around is declined, still no dialogue turn, nothing assigned
		NpcService.ChatOutcome work = npc.onPlayerChat(player, "gather some wood").join();
		expect(work.intent().isPresent() && work.intent().get().action().isEmpty() && work.turn().isEmpty(), "impossible work is declined with a reason: " + work);
		rt.setIntentPlanner(dev.luminary.core.npc.intent.IntentPlanner.none());
		NpcService.ChatOutcome none = npc.onPlayerChat(player, "gather some wood").join();
		expect(none.intent().isEmpty() && none.turn().isPresent(), "with planner none every line is dialogue: " + none);
		expect(npc.roster().get(id).flatMap(NpcRoster.NpcRecord::task).isEmpty(), "no task may exist");
		rt.setIntentPlanner(new KeywordIntentPlanner(TaskRules.DEFAULTS));
		npc.despawn(id);
		ctx.complete();
	}

	/** Autonomy (slice 10): a standing order and the server/NPC switches make the NPC stock the chest with no command or chat at all. */
	@GameTest(templateName = ARENA, tickLimit = 1600)
	public void standingOrderMakesTheNpcStockTheChestOnItsOwnThenRest(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		Arena a = arena(ctx, "auto_stock", 2);
		AutonomyState auto = a.npc().roster().ensureRecord(a.npcId()).autonomy();
		auto.addOrder(new StandingOrder.StockWood(a.chest().asLong(), 2));
		// NPC switch on, server switch still off: nothing may happen
		auto.setEnabled(true);
		expect(!rt.npc().autonomyEnabled(), "fixture: the server-wide switch is off by default");
		ctx.waitAndRun(120, () -> {
			expect(task(a).isEmpty(), "with the server switch off the NPC must not start anything");
			rt.setAutonomyEnabled(true);
			await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 1300, () -> {
				NpcTask t = task(a).orElseThrow();
				expect(t.requestedBy().isEmpty(), "an autonomous task has no requester");
				expect(t.state() == TaskState.COMPLETED && t.action() instanceof NpcAction.GatherWood g && g.maxLogs() == 2,
						"the order must become a completed 2-log gather, got " + t.action() + " " + t.state() + " " + t.failureReason().orElse(""));
				expect(logsIn(ctx, a.chest()) == 2, "the chest must now hold 2 logs, holds " + logsIn(ctx, a.chest()));
				expect(auto.decisionsWithin(java.time.Duration.ofHours(1), java.time.Instant.now()) == 1, "one decision must be on the budget");
				MemoryStore memory = a.npc().dialogue().memory(a.npcId());
				expect(memory.all().stream().anyMatch(e -> e.kind() == MemoryKind.SIGNIFICANT_EVENT && e.text().startsWith("Completed") && e.text().contains("gather 2 logs")),
						"the outcome must be remembered: " + memory.all());
				// order satisfied: the next think ticks decide nothing new
				ctx.waitAndRun(220, () -> {
					expect(task(a).map(x -> x.id().equals(t.id())).orElse(false), "a satisfied order must not start another task");
					rt.setAutonomyEnabled(false);
					a.npc().despawn(a.npcId());
					ctx.complete();
				});
			});
		});
	}

	/** Slice 11: a real furnace. Cobblestone and coal leave the chest, stone comes back, every count reconciles. */
	@GameTest(templateName = ARENA, tickLimit = 2400)
	public void smeltTakesInputAndFuelRunsTheFurnaceAndReturnsTheOutput(TestContext ctx) {
		Arena a = arena(ctx, "act_smelt", 0);
		BlockPos furnaceRel = new BlockPos(6, FLOOR_Y + 1, 9);
		ctx.setBlockState(furnaceRel, Blocks.FURNACE.getDefaultState());
		BlockPos furnace = ctx.getAbsolutePos(furnaceRel);
		Inventory inv = chest(ctx, a.chest());
		inv.setStack(0, new ItemStack(Items.COBBLESTONE, 3));
		inv.setStack(1, new ItemStack(Items.COAL, 2));
		inv.markDirty();
		// refusals: not smeltable, no fuel (after we look), wrong block
		expect(a.npc().assign(a.npcId(), new NpcAction.Smelt(furnace.asLong(), a.chest().asLong(), "minecraft:stick", 1)).reasonKey().equals("luminary.task.not_smeltable"), "sticks do not smelt");
		expect(a.npc().assign(a.npcId(), new NpcAction.Smelt(a.chest().asLong(), a.chest().asLong(), "minecraft:cobblestone", 1)).reasonKey().equals("luminary.task.bad_arguments"), "furnace must differ from container");
		expect(a.npc().assign(a.npcId(), new NpcAction.Smelt(a.log0().asLong(), a.chest().asLong(), "minecraft:cobblestone", 1)).reasonKey().equals("luminary.task.target_not_furnace"), "air is not a furnace");
		Result r = a.npc().assign(a.npcId(), new NpcAction.Smelt(furnace.asLong(), a.chest().asLong(), "minecraft:cobblestone", 3));
		expect(r.ok(), "smelt refused: " + r.reasonKey());
		await(ctx, () -> task(a).map(t -> !t.isActive()).orElse(false), 2300, () -> {
			NpcTask t = task(a).orElseThrow();
			expect(t.state() == TaskState.COMPLETED, "smelt should complete, got " + t.state() + " " + t.failureReason().orElse("") + " ledger=" + t.ledger().smelted());
			expect(t.ledger().smeltedTotal() == 3 && t.ledger().smelted().getOrDefault("minecraft:stone", 0) == 3, "3 stone must be smelted: " + t.ledger().smelted());
			expect(t.ledger().withdrawn().getOrDefault("minecraft:cobblestone", 0) == 3 && t.ledger().withdrawn().getOrDefault("minecraft:coal", 0) == 1,
					"3 cobblestone and exactly 1 coal (1600 ticks covers 3 x 200) must have been taken: " + t.ledger().withdrawn());
			Inventory chest = chest(ctx, a.chest());
			expect(count(chest, Items.STONE) == 3, "chest must hold 3 stone, holds " + count(chest, Items.STONE));
			expect(count(chest, Items.COBBLESTONE) == 0, "no cobblestone may remain in the chest");
			expect(count(chest, Items.COAL) == 1, "one coal must remain in the chest, remains " + count(chest, Items.COAL));
			expect(a.body().heldCount() == 0, "the NPC must carry nothing afterwards, carries " + a.body().heldCount());
			BlockEntity be = ctx.getWorld().getBlockEntity(furnace);
			expect(be instanceof Inventory f && f.getStack(0).isEmpty() && f.getStack(2).isEmpty(), "furnace input and output must be empty");
			a.npc().despawn(a.npcId());
			ctx.complete();
		});
	}

	/** Slice 13: a chest handed over by chat while looking at it, and the NPC stopping for a player who looks at it. */
	@GameTest(templateName = ARENA, tickLimit = 400)
	public void chestHandedOverByChatWhileLookingAtItAndNpcAttendsAWatchingPlayer(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		rt.setIntentPlanner(new KeywordIntentPlanner(TaskRules.DEFAULTS));
		NpcService npc = rt.npc();
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/intent_grant");
		ctx.setBlockState(CHEST, Blocks.CHEST.getDefaultState());
		BlockPos chest = ctx.getAbsolutePos(CHEST);
		Vec3d spawn = Vec3d.ofBottomCenter(ctx.getAbsolutePos(NPC_SPAWN));
		expect(npc.spawn(id, ctx.getWorld(), spawn).ok(), "spawn failed");
		LuminaryNpcEntity body = npc.body(id).orElseThrow();
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		// stand two blocks west of the chest, look at it
		Vec3d stand = Vec3d.ofBottomCenter(chest.west(2));
		face(player, stand, -90.0f, 20.0f);   // yaw -90 faces +x (east)
		expect(!npc.roster().ensureRecord(id).authorizedContainers().contains(chest.asLong()), "fixture: chest not yet authorized");
		net.minecraft.util.hit.HitResult hit = player.raycast(6.0, 1.0f, false);
		NpcService.ChatOutcome out = npc.onPlayerChat(player, "Lumin, use this chest").join();
		expect(out.intent().isPresent() && out.intent().get().grant().isPresent(), "a handover intent is expected: " + out
				+ " | player at " + player.getPos() + " eye " + player.getEyePos() + " yaw " + player.getYaw() + " pitch " + player.getPitch()
				+ " rot " + player.getRotationVec(1.0f) + " hit " + hit.getType() + " " + hit.getPos() + " chest " + chest);
		expect(out.assignment().map(Result::ok).orElse(false), "the grant must be accepted: " + out);
		expect(npc.roster().ensureRecord(id).authorizedContainers().contains(chest.asLong()), "the looked-at chest must be authorized");
		expect(npc.dialogue().memory(id).all().stream().anyMatch(m -> m.text().contains("gave me the container")), "the handover must be remembered");
		// looking away: nothing to hand over
		face(player, stand, 90.0f, 0.0f);   // facing west, away from the chest
		NpcService.ChatOutcome away = npc.onPlayerChat(player, "use this chest").join();
		expect(away.intent().isPresent() && away.intent().get().grant().isEmpty() && away.intent().get().replyKey().equals(Optional.of(dev.luminary.core.npc.intent.Intent.REPLY_LOOK_AT_CONTAINER)),
				"without a chest in view the NPC asks which one: " + away);
		// take it back
		face(player, stand, -90.0f, 20.0f);
		NpcService.ChatOutcome back = npc.onPlayerChat(player, "forget this chest").join();
		expect(back.assignment().map(Result::ok).orElse(false) && !npc.roster().ensureRecord(id).authorizedContainers().contains(chest.asLong()), "the chest must be revoked: " + back);
		// attending: the player faces the NPC from 3 blocks → the NPC should be stopped within a second
		Vec3d npcPos = body.getPos();
		Vec3d p2 = new Vec3d(npcPos.x + 3, npcPos.y, npcPos.z);
		face(player, p2, 90.0f, 0.0f);   // yaw 90 faces -x, towards the NPC
		expect(dev.luminary.fabric.npc.AttendPlayerGoalAccess.isLookingAt(player, body), "the gaze test must see the NPC");
		face(player, p2, -90.0f, 0.0f);
		expect(!dev.luminary.fabric.npc.AttendPlayerGoalAccess.isLookingAt(player, body), "facing away is not looking");
		face(player, p2, 90.0f, 0.0f);
		ctx.waitAndRun(30, () -> {
			expect(body.getNavigation().isIdle(), "an attended NPC must not be walking");
			expect(body.squaredDistanceTo(npcPos) < 1.5, "an attended NPC must stay where it was, moved " + Math.sqrt(body.squaredDistanceTo(npcPos)));
			npc.despawn(id);
			ctx.complete();
		});
	}
}
