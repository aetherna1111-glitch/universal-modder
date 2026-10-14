package dev.luminary.gametest;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskState;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.GearManager;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Slice 14: addressed by name at any distance, handed tools and armour, an axe used and worn for real. */
public final class GearGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	private static final int FLOOR_Y = 1;
	private static final BlockPos NPC_SPAWN = new BlockPos(2, FLOOR_Y + 1, 2);
	private static final BlockPos CHEST = new BlockPos(10, FLOOR_Y + 1, 2);
	private static final BlockPos LOG0 = new BlockPos(6, FLOOR_Y + 1, 6);

	private static void expect(boolean c, String m) {
		if (!c) throw new GameTestException(m);
	}

	private static void await(TestContext ctx, BooleanSupplier done, int budget, Runnable then) {
		if (done.getAsBoolean()) { then.run(); return; }
		if (budget <= 0) throw new GameTestException("condition not reached in time");
		ctx.waitAndRun(5, () -> await(ctx, done, budget - 5, then));
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void chatReachesTheNpcOnlyByNameAndThenAtAnyDistance(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		NpcService npc = rt.npc();
		npc.configureChat(true, 0);
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/named");
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		expect(npc.spawn(id, ctx.getWorld(), pos).ok(), "spawn failed");
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		player.refreshPositionAndAngles(pos.x + 2, pos.y, pos.z, 0, 0);
		expect(!npc.onPlayerChat(player, "hello there, anyone?").join().heard(), "a line without the name must be ignored even up close");
		expect(npc.onPlayerChat(player, "Vesna, hello!").join().heard(), "the alias must be heard");
		player.refreshPositionAndAngles(pos.x + 60, pos.y, pos.z, 0, 0);
		expect(npc.onPlayerChat(player, "Весна, ты меня слышишь?").join().heard(), "addressed by name, distance does not matter");
		expect(!npc.onPlayerChat(player, "lumin, are you there?").join().heard(), "another NPC's name is not this one's");
		npc.despawn(id);
		ctx.complete();
	}

	@GameTest(templateName = ARENA, tickLimit = 1400)
	public void handedToolsAndArmourAreKeptWornAndUsed(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		NpcService npc = rt.npc();
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/gear");
		ctx.setBlockState(CHEST, Blocks.CHEST.getDefaultState());
		for (int i = 0; i < 2; i++) ctx.setBlockState(LOG0.up(i), Blocks.OAK_LOG.getDefaultState());
		expect(npc.spawn(id, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(NPC_SPAWN))).ok(), "spawn failed");
		LuminaryNpcEntity body = npc.body(id).orElseThrow();
		npc.authorizeContainer(id, ctx.getAbsolutePos(CHEST).asLong());
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		// a tool is taken into the bag
		ItemStack axe = new ItemStack(Items.IRON_AXE);
		expect(npc.offer(body, player, axe) == GearManager.Offer.TOOK_TOOL, "an axe is taken");
		expect(axe.isEmpty(), "the player's single axe is gone");
		expect(GearManager.describe(body).contains("iron_axe"), "the axe is in the bag: " + GearManager.describe(body));
		// armour is worn; a second piece for the same slot is refused and stays with the player
		ItemStack helmet = new ItemStack(Items.IRON_HELMET, 2);
		expect(npc.offer(body, player, helmet) == GearManager.Offer.WEARING, "a helmet is worn");
		expect(helmet.getCount() == 1 && body.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET), "one helmet taken, one left");
		expect(npc.offer(body, player, helmet) == GearManager.Offer.ARMOR_INTACT, "a second helmet is refused while the first is whole");
		expect(helmet.getCount() == 1, "the refused helmet stays with the player");
		expect(npc.offer(body, player, new ItemStack(Items.DIRT)) == GearManager.Offer.NOT_GEAR, "dirt is not gear");
		// the worn helmet breaks → the next one is accepted
		body.getEquippedStack(EquipmentSlot.HEAD).setDamage(body.getEquippedStack(EquipmentSlot.HEAD).getMaxDamage() - 1);
		body.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);   // what vanilla does when the last durability point goes
		expect(npc.offer(body, player, helmet) == GearManager.Offer.WEARING, "after a break the next helmet is accepted");
		// gathering uses the axe: faster than bare hands, durability spent, axe back in the bag afterwards
		Result r = npc.assign(id, new NpcAction.GatherWood(ctx.getAbsolutePos(LOG0).asLong(), ctx.getAbsolutePos(CHEST).asLong(), 2));
		expect(r.ok(), "assign failed: " + r.reasonKey());
		await(ctx, () -> npc.roster().get(id).flatMap(NpcRoster.NpcRecord::task).map(t -> !t.isActive()).orElse(false), 1300, () -> {
			NpcTask t = npc.roster().get(id).flatMap(NpcRoster.NpcRecord::task).orElseThrow();
			expect(t.state() == TaskState.COMPLETED && t.ledger().blocksBroken() == 2, "2 logs expected, got " + t.state() + " " + t.ledger().blocksBroken());
			expect(body.getMainHandStack().isEmpty(), "the axe must be stowed after the work, hand=" + body.getMainHandStack());
			Optional<ItemStack> stowed = Optional.empty();
			for (int i = 0; i < body.heldItems().size(); i++) {
				if (body.heldItems().getStack(i).isOf(Items.IRON_AXE)) stowed = Optional.of(body.heldItems().getStack(i));
			}
			expect(stowed.isPresent() && stowed.get().getDamage() == 2, "the axe must be back in the bag with 2 durability spent: " + GearManager.describe(body));
			npc.despawn(id);
			ctx.complete();
		});
	}
}
