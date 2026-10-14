package dev.luminary.fabric.npc;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;

/**
 * The Seal of the Envoy: the one thing no trader sells, and Lumin sells it once. Used, it calls a small "Blessing":
 * every player within {@value #RADIUS} blocks (the user included) is healed {@value #HEAL} and shielded for a while,
 * every monster in the same circle is weakened and lit up. Cooldown {@value #COOLDOWN_TICKS} ticks. Never consumed.
 */
public final class EnvoySealItem extends Item {
	public static final double RADIUS = 6.0;
	public static final float HEAL = 6.0f;
	public static final int COOLDOWN_TICKS = 20 * 90;

	public EnvoySealItem(Settings settings) {
		super(settings);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (user.getItemCooldownManager().isCoolingDown(this)) {
			return TypedActionResult.fail(stack);
		}
		if (world instanceof ServerWorld sw) {
			blessing(sw, user);
			user.getItemCooldownManager().set(this, COOLDOWN_TICKS);
		}
		user.swingHand(hand);
		return TypedActionResult.success(stack, world.isClient);
	}

	/** The blessing around {@code center}; also used by Lumin's harp. */
	public static void blessing(ServerWorld world, LivingEntity center) {
		Box box = center.getBoundingBox().expand(RADIUS);
		for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box, le -> le.squaredDistanceTo(center) <= RADIUS * RADIUS)) {
			if (e instanceof PlayerEntity || e == center) {
				e.heal(HEAL);
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 20 * 20, 1));
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 8, 0));
			} else if (e instanceof Monster) {
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 8, 1));
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 8, 0));
			}
		}
		world.playSound(null, center.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.4f);
		world.spawnParticles(ParticleTypes.END_ROD, center.getX(), center.getY() + 1.0, center.getZ(), 40, RADIUS * 0.5, 0.8, RADIUS * 0.5, 0.02);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("item.luminary.envoy_seal.lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable("item.luminary.envoy_seal.use.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable("item.luminary.envoy_seal.use.text", (int) RADIUS, (int) (HEAL / 2)).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + COOLDOWN_TICKS / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable("item.luminary.envoy_seal.unique").formatted(Formatting.GOLD));
	}
}
