package dev.luminary.gametest;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.HolySwordItem;
import dev.luminary.fabric.npc.LuminaryItems;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/** The Holy Sword of Lumin in a player's hands: Witness marks, the Verdict beam, the Sanctuary circle, cooldowns. */
public final class SwordGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	private static final int FLOOR_Y = 1;

	private static void expect(boolean c, String m) {
		if (!c) throw new GameTestException(m);
	}

	private static double attackDamage(ItemStack stack) {
		return stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_ATTACK_DAMAGE).stream().mapToDouble(m -> m.getValue()).sum() + 1.0;
	}

	@GameTest(templateName = ARENA, tickLimit = 400)
	public void witnessMarksRaiseTheDamageAndVerdictSpendsThemInABeam(TestContext ctx) {
		ItemStack sword = HolySwordItem.bound(LuminaryItems.LUMIN_BLADE, LuminaryId.parse("luminary:npc/lumin"), UUID.randomUUID());
		expect(Math.abs(attackDamage(sword) - HolySwordItem.BASE_DAMAGE) < 0.01, "9 damage with an empty ledger: " + attackDamage(sword));
		expect(!sword.isDamageable() && sword.getMaxDamage() == 0, "unbreakable");
		expect(net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.SHARPNESS, sword) == 6
				&& net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.SMITE, sword) == 7
				&& net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.LOOTING, sword) == 4, "the vanilla blessings are minted onto the sword: " + sword.getEnchantments());
		expect(sword.getEnchantments().size() == 6, "on a bare server only the 6 vanilla blessings apply; modded ids are skipped: " + sword.getEnchantments().size());
		HolySwordItem.setBlessings(java.util.List.of("minecraft:sharpness:2", "nope", "nosuchmod:thing:3", "minecraft:unbreaking:x"));
		ItemStack custom = HolySwordItem.bound(LuminaryItems.LUMIN_BLADE, LuminaryId.parse("luminary:npc/lumin"), UUID.randomUUID());
		expect(custom.getEnchantments().size() == 1 && net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.SHARPNESS, custom) == 2, "configured blessings, malformed entries ignored");
		HolySwordItem.setBlessings(HolySwordItem.DEFAULT_BLESSINGS);
		expect(HolySwordItem.hasRune(sword, HolySwordItem.RUNE_CLARITY) && HolySwordItem.hasRune(sword, HolySwordItem.RUNE_EMBERS), "both sockets filled");
		ItemStack free = HolySwordItem.unbound(LuminaryItems.LUMIN_BLADE);
		expect(!HolySwordItem.isBound(free) && free.getEnchantments().size() == 6 && HolySwordItem.hasRune(free, HolySwordItem.RUNE_EMBERS) && Math.abs(attackDamage(free) - HolySwordItem.BASE_DAMAGE) < 0.01, "a creative-tab blade is fully prepared but bound to nobody");
		ItemStack plain = new ItemStack(LuminaryItems.LUMIN_BLADE);
		plain.inventoryTick(ctx.getWorld(), ctx.createMockSurvivalPlayer(), 0, false);
		expect(plain.getNbt() != null && plain.getNbt().getBoolean(HolySwordItem.NBT_PREPARED) && HolySwordItem.hasRune(plain, HolySwordItem.RUNE_CLARITY), "a /give blade prepares itself on its first inventory tick");
		LuminaryRuntime.get().orElseThrow().npc().behaviour().reconcileHeldBlade(free, ctx.createMockSurvivalPlayer());
		expect(!free.isEmpty(), "a free blade is never purged");
		expect(sword.getAttributeModifiers(EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_LUCK).stream().mapToDouble(m -> m.getValue()).sum() == 1.0, "Rune of Clarity: +1 luck");
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		Vec3d at = ctx.getAbsolute(new Vec3d(4.5, FLOOR_Y + 1, 8.5));
		player.refreshPositionAndAngles(at.x, at.y, at.z, -90f, 0f);   // looking +X
		player.setHeadYaw(-90f);
		player.prevYaw = -90f;
		player.prevPitch = 0f;
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		player.setStackInHand(Hand.MAIN_HAND, sword);
		CowEntity cow = ctx.spawnEntity(EntityType.COW, new BlockPos(8, FLOOR_Y + 1, 8));
		ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(12, FLOOR_Y + 1, 8));
		zombie.setAiDisabled(true);
		cow.setAiDisabled(true);
		for (int i = 0; i < 9; i++) {
			sword.getItem().postHit(sword, cow, player);
		}
		expect(HolySwordItem.evidence(sword) == HolySwordItem.MAX_EVIDENCE, "marks cap at 7: " + HolySwordItem.evidence(sword));
		expect(Math.abs(attackDamage(sword) - (HolySwordItem.BASE_DAMAGE + 3.5)) < 0.01, "+0.5 per mark: " + attackDamage(sword));
		float cowBefore = cow.getHealth();
		float zombieBefore = zombie.getHealth();
		expect(sword.use(ctx.getWorld(), player, Hand.MAIN_HAND).getResult().isAccepted(), "verdict fires");
		expect(HolySwordItem.evidence(sword) == 0, "marks spent");
		expect(player.getItemCooldownManager().isCoolingDown(sword.getItem()), "on cooldown");
		expect(!sword.use(ctx.getWorld(), player, Hand.MAIN_HAND).getResult().isAccepted(), "no second verdict on cooldown or without marks");
		// the marked take a quarter more as holy damage that ignores armour; embers kindle
		ZombieEntity marked = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(4, FLOOR_Y + 1, 4));
		marked.setAiDisabled(true);
		marked.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.GLOWING, 200));
		float mb = marked.getHealth();
		expect(marked.damage(ctx.getWorld().getDamageSources().playerAttack(player), 4f), "a plain 4-damage hit lands");
		float afterPlain = marked.getHealth();
		sword.getItem().postHit(sword, marked, player);
		float bonus = HolySwordItem.holyBonus(sword);
		expect(Math.abs((afterPlain - marked.getHealth()) - bonus) < 0.01f, "a quarter of the sword's damage follows as holy damage despite the hit's invulnerability frames: " + afterPlain + " -> " + marked.getHealth() + " expected -" + bonus + " (from " + mb + ")");
		expect(marked.isOnFire(), "the Rune of Embers kindles the target");
		ctx.waitAndRun(2, () -> {
			float expected = HolySwordItem.VERDICT_BASE + HolySwordItem.VERDICT_PER_EVIDENCE * HolySwordItem.MAX_EVIDENCE;   // 17
			expect(cowBefore - cow.getHealth() >= Math.min(expected, cowBefore) - 0.01f || !cow.isAlive(), "the cow in line was struck: " + cowBefore + " -> " + cow.getHealth());
			expect(!zombie.isAlive() || zombieBefore - zombie.getHealth() >= 17f, "the undead farther along the line took the beam too: " + zombieBefore + " -> " + zombie.getHealth());
			expect(!zombie.isAlive() || zombie.isOnFire(), "undead burn");
			expect(!zombie.isAlive() || zombie.hasStatusEffect(StatusEffects.GLOWING), "struck things glow");
			ctx.complete();
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 200)
	public void theLightOfTheCatalogHurtsAndMarksHostilesAroundTheBearer(TestContext ctx) {
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		Vec3d at = ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 8.5));
		player.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		ZombieEntity near = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(10, FLOOR_Y + 1, 8));
		ZombieEntity far = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(20, FLOOR_Y + 1, 20));
		CowEntity cow = ctx.spawnEntity(EntityType.COW, new BlockPos(8, FLOOR_Y + 1, 10));
		near.setAiDisabled(true);
		far.setAiDisabled(true);
		float nb = near.getHealth(), fb = far.getHealth(), cb = cow.getHealth();
		int touched = HolySwordItem.aura(ctx.getWorld(), player);
		expect(touched == 1, "one hostile within 4 blocks: " + touched);
		expect(nb - near.getHealth() >= 2f - 0.01f && near.hasStatusEffect(StatusEffects.GLOWING), "the undead nearby takes 2 and glows: " + nb + " -> " + near.getHealth());
		expect(far.getHealth() == fb && cow.getHealth() == cb, "the far zombie and the cow are untouched");
		ctx.complete();
	}

	@GameTest(templateName = ARENA, tickLimit = 400)
	public void sanctuaryShieldsAlliesAndWeakensMonstersInsideItsCircle(TestContext ctx) {
		ItemStack sword = HolySwordItem.bound(LuminaryItems.LUMIN_BLADE, LuminaryId.parse("luminary:npc/lumin"), UUID.randomUUID());
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		Vec3d at = ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 8.5));
		player.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		player.setStackInHand(Hand.MAIN_HAND, sword);
		player.setSneaking(true);
		expect(!sword.use(ctx.getWorld(), player, Hand.MAIN_HAND).getResult().isAccepted(), "needs three marks");
		HolySwordItem.setEvidence(sword, 4, ctx.getWorld().getTime());   // set directly: a Revelation on a hit would add a second mark
		ZombieEntity inside = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(10, FLOOR_Y + 1, 8));
		ZombieEntity outside = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(20, FLOOR_Y + 1, 20));
		inside.setAiDisabled(true);
		outside.setAiDisabled(true);
		int before = LuminaryRuntime.get().orElseThrow().npc().behaviour().sanctuaries().active();
		expect(sword.use(ctx.getWorld(), player, Hand.MAIN_HAND).getResult().isAccepted(), "sanctuary planted");
		expect(HolySwordItem.evidence(sword) == 1, "three marks spent, one left: " + HolySwordItem.evidence(sword));
		expect(LuminaryRuntime.get().orElseThrow().npc().behaviour().sanctuaries().active() == before + 1, "one circle active");
		ctx.waitAndRun(25, () -> {
			expect(player.hasStatusEffect(StatusEffects.REGENERATION) && player.hasStatusEffect(StatusEffects.RESISTANCE), "the bearer is shielded");
			expect(inside.hasStatusEffect(StatusEffects.WEAKNESS) && inside.hasStatusEffect(StatusEffects.GLOWING), "the monster inside is weakened");
			expect(!outside.hasStatusEffect(StatusEffects.WEAKNESS), "the monster outside is untouched");
			ctx.waitAndRun(HolySwordItem.SANCTUARY_TICKS, () -> {
				expect(LuminaryRuntime.get().orElseThrow().npc().behaviour().sanctuaries().active() == before, "the circle fades");
				ctx.complete();
			});
		});
	}
}
