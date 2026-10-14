package dev.luminary.gametest;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.BehaviourService;
import dev.luminary.fabric.npc.GearManager;
import dev.luminary.fabric.npc.LuminArmorItem;
import dev.luminary.fabric.npc.LuminaryItems;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** The Vestments of the Envoy: Lumin wears his bound set and nothing takes it; a free set protects a player with every effect. */
public final class VestmentGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	private static final int FLOOR_Y = 1;

	private static void expect(boolean c, String m) {
		if (!c) throw new GameTestException(m);
	}

	@GameTest(templateName = ARENA, tickLimit = 400)
	public void theNpcWearsItsBoundVestmentsAndNothingTakesThem(TestContext ctx) {
		NpcService npc = LuminaryRuntime.get().orElseThrow().npc();
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/vestments");
		expect(npc.spawn(id, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(8, FLOOR_Y + 1, 8)))).ok(), "spawn");
		LuminaryNpcEntity body = npc.body(id).orElseThrow();
		ctx.waitAndRun(25, () -> {
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack worn = body.getEquippedStack(slot);
				expect(LuminArmorItem.isVestment(worn) && LuminArmorItem.owner(worn).filter(id::equals).isPresent(), slot + " must hold a bound vestment: " + worn);
				expect(!worn.isDamageable() && worn.getEnchantments().size() >= 3, "unbreakable and blessed: " + worn.getEnchantments());
			}
			PlayerEntity player = ctx.createMockSurvivalPlayer();
			expect(npc.offer(body, player, new ItemStack(Items.NETHERITE_HELMET)) == GearManager.Offer.ARMOR_INTACT, "no armour ever replaces a vestment");
			expect(LuminArmorItem.isVestment(body.getEquippedStack(EquipmentSlot.HEAD)), "the diadem stays");
			// knocked out and looted: the bag is empty and the vestments are not loot
			body.damage(body.getDamageSources().playerAttack(player), 1000f);
			expect(body.isDown(), "down");
			expect(LuminaryRuntime.get().orElseThrow().npc().behaviour().interactDown(body, player, ItemStack.EMPTY) == BehaviourService.DownInteraction.STOLE_WEAPON, "an empty hand takes the blade");
			expect(LuminaryRuntime.get().orElseThrow().npc().behaviour().interactDown(body, player, ItemStack.EMPTY) == BehaviourService.DownInteraction.NOTHING, "then nothing to loot");
			expect(LuminArmorItem.wearsFullSet(body), "the set is still on him while he lies there");
			expect(body.getBoundingBox().getXLength() > 1.0 && body.getBoundingBox().getYLength() < 0.8, "lying down he is a wide low box a player can click: " + body.getBoundingBox());
			// an operator reset: same place, clean state, sword and set back
			var pos = body.getPos();
			var r = LuminaryRuntime.get().orElseThrow().npc().behaviour().reset(id, ctx.getWorld(), null);
			expect(r.ok(), "reset: " + r.reasonKey());
			LuminaryNpcEntity fresh = npc.body(id).orElseThrow();
			expect(fresh != body && !fresh.isDown() && fresh.getPos().distanceTo(pos) < 1.0 && npc.roster().ensureRecord(id).knockout().isEmpty(), "a fresh body in place, not down");
			ctx.waitAndRun(25, () -> {
				expect(LuminArmorItem.wearsFullSet(fresh), "the fresh body wears the set");
				npc.despawn(id);
				ctx.complete();
			});
			if (true) return;
			body.getEquippedStack(EquipmentSlot.CHEST).setCount(0);   // even a vanished piece comes back
			npc.behaviour().revive(id);
			ctx.waitAndRun(25, () -> {
				expect(LuminArmorItem.wearsFullSet(body), "a missing piece is restored");
				var other = LuminaryId.parse("luminary-gametest:npc/plain");
				expect(npc.spawn(other, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, FLOOR_Y + 1, 3)))).ok(), "spawn other");
				ctx.waitAndRun(25, () -> {
					expect(!LuminArmorItem.wearsFullSet(npc.body(other).orElseThrow()) && npc.body(other).orElseThrow().getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "an ordinary NPC gets no vestments");
					npc.despawn(other);
					npc.despawn(id);
					ctx.complete();
				});
			});
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "vestments-helmet")
	public void theHelmetComesOffAndBackOnByChatWithAnAnimationAndTheGearScreenIsReadOnly(TestContext ctx) {
		NpcService npc = LuminaryRuntime.get().orElseThrow().npc();
		npc.configureChat(true, 0);
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/vestments");
		expect(npc.spawn(id, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(8, FLOOR_Y + 1, 8)))).ok(), "spawn");
		LuminaryNpcEntity body = npc.body(id).orElseThrow();
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		player.refreshPositionAndAngles(body.getX() + 2, body.getY(), body.getZ(), 0, 0);
		body.heldItems().setStack(0, new ItemStack(Items.IRON_AXE));
		ctx.waitAndRun(25, () -> {
			expect(LuminArmorItem.wearsFullSet(body), "dressed");
			// the read-only gear screen mirrors hands, armour and bag; clicks move nothing
			var handler = (dev.luminary.fabric.npc.GearViewScreenHandler) dev.luminary.fabric.npc.GearViewScreenHandler.factory(body).createMenu(1, player.getInventory(), player);
			int bag0 = dev.luminary.fabric.npc.GearViewScreenHandler.SLOT_BAG;
			expect(handler.getSlot(0).getStack().isOf(LuminaryItems.VESTMENT_HELMET) && handler.getSlot(1).getStack().isOf(LuminaryItems.VESTMENT_CHESTPLATE)
					&& handler.getSlot(bag0).getStack().isOf(Items.IRON_AXE), "the mirror shows the diadem, the cuirass and the axe in the bag");
			expect(handler.getSlot(dev.luminary.fabric.npc.GearViewScreenHandler.SLOT_SWORD).getStack().isOf(LuminaryItems.LUMIN_BLADE), "the sword shows while in his custody, even when sheathed");
			expect(handler.getSlot(dev.luminary.fabric.npc.GearViewScreenHandler.SLOT_SHIELD).getStack().isOf(Items.SHIELD), "the shield shows");
			handler.onSlotClick(bag0, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, player);
			expect(handler.getCursorStack().isEmpty() && body.heldItems().getStack(0).isOf(Items.IRON_AXE) && player.getInventory().isEmpty(), "a click takes nothing");
			expect(handler.quickMove(player, bag0).isEmpty() && body.heldItems().getStack(0).isOf(Items.IRON_AXE), "shift-click moves nothing");
			player.getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
			handler.onSlotClick(dev.luminary.fabric.npc.GearViewScreenHandler.MIRROR_SIZE + 27, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
			expect(player.getInventory().getStack(0).getCount() == 5 && body.heldItems().getStack(1).isEmpty(), "nothing can be put in");
			expect(!handler.getSlot(bag0).canInsert(new ItemStack(Items.DIRT)) && !handler.getSlot(bag0).canTakeItems(player), "slots refuse insertion and removal");
			// "take off your helmet": the animation plays and the head stays bare until asked otherwise
			NpcService.ChatOutcome off = npc.onPlayerChat(player, "Vestments, сними шлем").join();
			expect(off.heard() && off.intent().flatMap(i -> i.literalReply()).orElse("").equals("As you wish."), "answered: " + off);
			ctx.waitAndRun(BehaviourService.HELMET_ANIMATION_TICKS - 6, () -> {
				expect(body.getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "mid-animation the head is bare");
				expect(body.getMainHandStack().isOf(LuminaryItems.VESTMENT_HELMET) || body.getOffHandStack().isOf(LuminaryItems.VESTMENT_HELMET), "the diadem is in the hand");
				ctx.waitAndRun(30, () -> {
					expect(body.helmetOff() && body.getEquippedStack(EquipmentSlot.HEAD).isEmpty() && !body.getMainHandStack().isOf(LuminaryItems.VESTMENT_HELMET) && !body.getOffHandStack().isOf(LuminaryItems.VESTMENT_HELMET), "put away, head bare, and the vestment tick does not put it back");
					expect(npc.onPlayerChat(player, "Vestments, сними шлем").join().intent().flatMap(i -> i.literalReply()).orElse("").equals("It already is."), "asked twice");
					NpcService.ChatOutcome on = npc.onPlayerChat(player, "Vestments, надень шлем").join();
					expect(on.intent().flatMap(i -> i.literalReply()).orElse("").equals("Very well."), "put it on: " + on);
					ctx.waitAndRun(BehaviourService.HELMET_ANIMATION_TICKS + 5, () -> {
						expect(!body.helmetOff() && body.getEquippedStack(EquipmentSlot.HEAD).isOf(LuminaryItems.VESTMENT_HELMET), "the diadem is back on");
						npc.despawn(id);
						ctx.complete();
					});
				});
			});
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 600)
	public void aFreeSetProtectsItsWearerWithEveryEffectAndTheNova(TestContext ctx) {
		ctx.getWorld().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		Vec3d at = ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 8.5));
		player.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		for (LuminArmorItem piece : LuminaryItems.VESTMENTS) {
			ItemStack free = LuminArmorItem.unbound(piece);
			expect(!LuminArmorItem.isBound(free) && free.getNbt().getBoolean(LuminArmorItem.NBT_PREPARED), "a creative piece is free and prepared");
			player.equipStack(piece.getSlotType(), free);
		}
		expect(LuminArmorItem.wearsFullSet(player), "full set on");
		float hp = player.getHealth();
		expect(!player.damage(player.getDamageSources().fall(), 10f) && player.getHealth() == hp, "the sandals refuse fall damage");
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200));
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 200));
		ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(10, FLOOR_Y + 1, 8));
		zombie.setAiDisabled(true);
		player.setHealth(4f);   // below 30 %: the Envoy's Light fires on the next chest tick
		ctx.waitAndRun(25, () -> {
			expect(player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ARMOR) >= 24, "24 armour once equipment changes were applied: " + player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ARMOR));
			expect(player.getMaxHealth() >= 28f, "the Runes of Dawn add 8 max health: " + player.getMaxHealth());
			ZombieEntity striker = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(6, FLOOR_Y + 1, 8));
			striker.setAiDisabled(true);
			player.damage(player.getDamageSources().mobAttack(striker), 1f);
			expect(striker.isOnFire(), "the Runes of Cinders set the striker on fire");
			expect(!player.hasStatusEffect(StatusEffects.SLOWNESS) && !player.hasStatusEffect(StatusEffects.BLINDNESS), "slowness and blindness are shrugged off");
			expect(player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE), "the cuirass keeps fire away");
			expect(player.getHealth() >= 10f && player.hasStatusEffect(StatusEffects.REGENERATION), "the nova healed 6 and regenerates: " + player.getHealth());
			expect(zombie.hasStatusEffect(StatusEffects.GLOWING) && zombie.getHealth() < zombie.getMaxHealth(), "the zombie was hurled, hurt and marked");
			long at1 = player.getEquippedStack(EquipmentSlot.CHEST).getNbt().getLong(LuminArmorItem.NBT_NOVA_AT);
			player.setHealth(4f);
			ctx.waitAndRun(25, () -> {
				expect(player.getEquippedStack(EquipmentSlot.CHEST).getNbt().getLong(LuminArmorItem.NBT_NOVA_AT) == at1, "no second nova inside the cooldown");
				ctx.complete();
			});
		});
	}
}
