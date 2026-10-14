package dev.luminary.fabric.npc;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import dev.luminary.core.ids.LuminaryId;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
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
import java.util.UUID;

/**
 * The Angelic Harp: the weapon Lumin takes up after his blade is given away for good. Struck, it is a light mace
 * ({@value #DAMAGE} damage); played (right-click), it sounds a Chord of Light: every monster within
 * {@value #RADIUS} blocks takes {@value #CHORD_DAMAGE} holy damage (the undead twice that), is slowed and lit up;
 * every player in the circle is healed {@value #CHORD_HEAL} and given regeneration. Cooldown {@value #COOLDOWN_TICKS}
 * ticks. A free harp comes from the creative tab; Lumin's own carries his name like the blade did.
 */
public final class AngelicHarpItem extends Item {
	public static final double DAMAGE = 6.0;
	public static final double ATTACK_SPEED = -2.0;
	public static final double RADIUS = 6.0;
	public static final float CHORD_DAMAGE = 7.0f;
	public static final float CHORD_HEAL = 6.0f;
	public static final int COOLDOWN_TICKS = 20 * 10;
	private static final UUID DAMAGE_MODIFIER_ID = UUID.fromString("5b0a6e2c-4c3f-4d8e-9a1b-2f6d7c8e9f01");
	private static final UUID SPEED_MODIFIER_ID = UUID.fromString("5b0a6e2c-4c3f-4d8e-9a1b-2f6d7c8e9f02");
	private final Multimap<EntityAttribute, EntityAttributeModifier> modifiers;

	public AngelicHarpItem(Settings settings) {
		super(settings);
		modifiers = ImmutableMultimap.of(
				EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(DAMAGE_MODIFIER_ID, "Weapon modifier", DAMAGE - 1.0, EntityAttributeModifier.Operation.ADDITION),
				EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(SPEED_MODIFIER_ID, "Weapon modifier", ATTACK_SPEED, EntityAttributeModifier.Operation.ADDITION));
	}

	/** Lumin's harp: unbreakable, named after him, never a thing a player keeps (it is not loot: it is in his hands only while he fights). */
	public static ItemStack bound(Item item, LuminaryId npc, String ownerNameKey) {
		ItemStack stack = new ItemStack(item);
		stack.getOrCreateNbt().putString(HolySwordItem.NBT_OWNER, npc.toString());
		stack.getOrCreateNbt().putBoolean("Unbreakable", true);
		return HolySwordItem.withOwnerName(stack, ownerNameKey);
	}

	@Override
	public Multimap<EntityAttribute, EntityAttributeModifier> getAttributeModifiers(EquipmentSlot slot) {
		return slot == EquipmentSlot.MAINHAND ? modifiers : super.getAttributeModifiers(slot);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}

	@Override
	public Text getName(ItemStack stack) {
		return HolySwordItem.shimmer(net.minecraft.util.Language.getInstance().get(getTranslationKey(stack)));
	}

	@Override
	public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 4, 0));
		return true;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (user.getItemCooldownManager().isCoolingDown(this)) {
			return TypedActionResult.fail(stack);
		}
		if (world instanceof ServerWorld sw) {
			chord(sw, user);
			user.getItemCooldownManager().set(this, COOLDOWN_TICKS);
		}
		user.swingHand(hand);
		return TypedActionResult.success(stack, world.isClient);
	}

	/** The Chord of Light around {@code player}. Returns how many monsters it struck. */
	public static int chord(ServerWorld world, LivingEntity player) {
		int struck = 0;
		Box box = player.getBoundingBox().expand(RADIUS);
		for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box, le -> le != player && le.squaredDistanceTo(player) <= RADIUS * RADIUS)) {
			if (e instanceof PlayerEntity || e instanceof LuminaryNpcEntity) {
				e.heal(CHORD_HEAL);
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 6, 0));
			} else if (e instanceof Monster) {
				float dmg = e.isUndead() ? CHORD_DAMAGE * 2 : CHORD_DAMAGE;
				e.timeUntilRegen = 0;
				if (e.damage(player instanceof PlayerEntity p ? world.getDamageSources().playerAttack(p) : world.getDamageSources().mobAttack(player), dmg)) {
					struck++;
				}
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 5, 1));
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 6, 0));
			}
		}
		player.heal(CHORD_HEAL * 0.5f);
		float[] pitches = {0.8f, 1.0f, 1.26f, 1.5f};
		for (float pitch : pitches) {
			world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 1.0f, pitch);
		}
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.9f);
		world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 60, RADIUS * 0.6, 1.0, RADIUS * 0.6, 0.03);
		world.spawnParticles(ParticleTypes.NOTE, player.getX(), player.getY() + 2.2, player.getZ(), 8, 0.6, 0.3, 0.6, 1.0);
		return struck;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		String k = "item.luminary.angelic_harp.";
		tooltip.add(Text.translatable("item.luminary.rarity.godlike").styled(st -> st.withColor(HolySwordItem.GODLIKE_COLOR).withBold(true)));
		HolySwordItem.ownerLine(stack).ifPresent(tooltip::add);
		tooltip.add(Text.translatable(k + "lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + "use.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable(k + "use.text", (int) RADIUS, (int) CHORD_DAMAGE, (int) (CHORD_DAMAGE * 2), (int) (CHORD_HEAL / 2)).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + COOLDOWN_TICKS / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.translatable(k + "hit").formatted(Formatting.BLUE));
	}
}
