package dev.luminary.fabric.npc;

import dev.luminary.core.ids.LuminaryId;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Vestments of the Envoy: Lumin's own armour, four pieces in the style of the Holy Sword. Unbreakable, bound to
 * the NPC (his pieces are never dropped, looted, stolen or replaced; a free set exists for the creative tab), each
 * with a unique parameter, and a set bonus when all four are worn:
 * <ul>
 *   <li><b>Diadem of Insight</b> (helmet): +1 luck; blindness, darkness and nausea cannot touch you.</li>
 *   <li><b>Cuirass of the Covenant</b> (chest): immune to fire; every {@value #WARD_PERIOD_TICKS} ticks a ward of
 *       {@value #WARD_ABSORPTION} absorption if none is left.</li>
 *   <li><b>Greaves of the Procession</b> (legs): +{@value #SPEED_BONUS} movement speed; slowness cannot touch you.</li>
 *   <li><b>Sandals of the Pilgrim</b> (boots): no fall damage; +{@value #STEP_SPEED_BONUS} movement speed.</li>
 *   <li><b>Set: the Envoy's Light.</b> Below {@value #NOVA_HEALTH_FRACTION} of health, once per {@value #NOVA_COOLDOWN_TICKS}
 *       ticks: a burst of light heals {@value #NOVA_HEAL}, grants Regeneration II, and hurls hostile things within
 *       {@value #NOVA_RADIUS} blocks away, marking them.</li>
 * </ul>
 */
public final class LuminArmorItem extends ArmorItem {
	public static final String NBT_OWNER = HolySwordItem.NBT_OWNER;
	public static final String NBT_PREPARED = "LuminaryPrepared";
	public static final String NBT_NOVA_AT = "LuminaryNovaAt";
	/** Runes in the two sockets of every piece. Dawn (runic): +{@value #DAWN_HEALTH} max health per piece. Cinders (netherfused): whoever strikes you burns, {@value #CINDER_SECONDS} s per piece. */
	public static final String NBT_RUNE = HolySwordItem.NBT_RUNE;
	public static final String NBT_NETHER_RUNE = HolySwordItem.NBT_NETHER_RUNE;
	public static final String RUNE_DAWN = "dawn";
	public static final String RUNE_CINDERS = "cinders";
	public static final double DAWN_HEALTH = 2.0;
	public static final int CINDER_SECONDS = 2;
	public static final int WARD_PERIOD_TICKS = 400;
	public static final float WARD_ABSORPTION = 4.0f;
	public static final double SPEED_BONUS = 0.10;
	public static final double STEP_SPEED_BONUS = 0.05;
	public static final double NOVA_HEALTH_FRACTION = 0.3;
	public static final int NOVA_COOLDOWN_TICKS = 1200;
	public static final float NOVA_HEAL = 6.0f;
	public static final double NOVA_RADIUS = 5.0;
	private static final UUID LUCK_ID = UUID.fromString("2c3a7a1e-5b9e-4f2a-9a2b-7c1d3e4f5a61");
	private static final UUID SPEED_LEGS_ID = UUID.fromString("3d4b8b2f-6cae-4a3b-8b3c-8d2e4f5a6b72");
	private static final UUID SPEED_BOOTS_ID = UUID.fromString("4e5c9c3a-7dbf-4b4c-9c4d-9e3f5a6b7c83");

	/** Blessings per piece as {@code namespace:path:level}; unknown ids are skipped (see {@link HolySwordItem#bless}). */
	public static final List<String> BLESSINGS_ALL = List.of("minecraft:protection:5", "minecraft:thorns:3", "minecraft:fire_protection:3",
			"zenith:berserkers_fury:3", "zenith:rebounding:3", "zenith:icy_thorns:3", "zenith:reflective:3");
	public static final List<String> BLESSINGS_HELMET = List.of("minecraft:respiration:3", "minecraft:aqua_affinity:1");
	public static final List<String> BLESSINGS_LEGS = List.of("minecraft:swift_sneak:3");
	public static final List<String> BLESSINGS_BOOTS = List.of("minecraft:feather_falling:4", "minecraft:depth_strider:3", "minecraft:soul_speed:3");

	public LuminArmorItem(Type type, Settings settings) {
		super(LuminArmorMaterial.INSTANCE, type, settings);
	}

	// ------------------------------------------------------------------ identity and preparation

	public static ItemStack bound(Item item, LuminaryId npc) {
		ItemStack stack = new ItemStack(item);
		stack.getOrCreateNbt().putString(NBT_OWNER, npc.toString());
		prepare(stack);
		return stack;
	}

	public static ItemStack unbound(Item item) {
		ItemStack stack = new ItemStack(item);
		prepare(stack);
		return stack;
	}

	public static Optional<LuminaryId> owner(ItemStack stack) {
		return HolySwordItem.owner(stack);
	}

	public static boolean isBound(ItemStack stack) {
		return owner(stack).isPresent();
	}

	public static boolean isVestment(ItemStack stack) {
		return stack.getItem() instanceof LuminArmorItem;
	}

	/** Attributes and blessings; idempotent. */
	public static void prepare(ItemStack stack) {
		if (!(stack.getItem() instanceof LuminArmorItem armor)) {
			return;
		}
		NbtCompound nbt = stack.getOrCreateNbt();
		if (nbt.getBoolean(NBT_PREPARED)) {
			return;
		}
		nbt.putBoolean(NBT_PREPARED, true);
		nbt.putBoolean("Unbreakable", true);
		nbt.putString(NBT_RUNE, RUNE_DAWN);
		nbt.putString(NBT_NETHER_RUNE, RUNE_CINDERS);
		EquipmentSlot slot = armor.getSlotType();
		nbt.remove("AttributeModifiers");
		for (var e : armor.getAttributeModifiers(slot).entries()) {
			stack.addAttributeModifier(e.getKey(), e.getValue(), slot);
		}
		if (hasRune(stack, RUNE_DAWN)) {
			stack.addAttributeModifier(EntityAttributes.GENERIC_MAX_HEALTH, new EntityAttributeModifier(UUID.nameUUIDFromBytes(("luminary:dawn/" + slot.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
					"Rune of Dawn", DAWN_HEALTH, EntityAttributeModifier.Operation.ADDITION), slot);
		}
		switch (slot) {
			case HEAD -> stack.addAttributeModifier(EntityAttributes.GENERIC_LUCK, new EntityAttributeModifier(LUCK_ID, "Diadem of Insight", 1.0, EntityAttributeModifier.Operation.ADDITION), slot);
			case LEGS -> stack.addAttributeModifier(EntityAttributes.GENERIC_MOVEMENT_SPEED, new EntityAttributeModifier(SPEED_LEGS_ID, "Greaves of the Procession", SPEED_BONUS, EntityAttributeModifier.Operation.MULTIPLY_TOTAL), slot);
			case FEET -> stack.addAttributeModifier(EntityAttributes.GENERIC_MOVEMENT_SPEED, new EntityAttributeModifier(SPEED_BOOTS_ID, "Sandals of the Pilgrim", STEP_SPEED_BONUS, EntityAttributeModifier.Operation.MULTIPLY_TOTAL), slot);
			default -> {
			}
		}
		List<String> extra = switch (slot) {
			case HEAD -> BLESSINGS_HELMET;
			case LEGS -> BLESSINGS_LEGS;
			case FEET -> BLESSINGS_BOOTS;
			default -> List.of();
		};
		List<String> all = new java.util.ArrayList<>(BLESSINGS_ALL);
		all.addAll(extra);
		HolySwordItem.blessWith(stack, all);
	}

	public static boolean hasRune(ItemStack stack, String rune) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && (rune.equals(nbt.getString(NBT_RUNE)) || rune.equals(nbt.getString(NBT_NETHER_RUNE)));
	}

	/** Pieces with the Rune of Cinders worn by {@code e}. */
	public static int cinderPieces(LivingEntity e) {
		int n = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (isVestment(e.getEquippedStack(slot)) && hasRune(e.getEquippedStack(slot), RUNE_CINDERS)) {
				n++;
			}
		}
		return n;
	}

	/** Rune of Cinders: a living attacker catches fire (hooked from {@code ServerLivingEntityEvents.ALLOW_DAMAGE}). */
	public static void cinders(LivingEntity victim, net.minecraft.entity.damage.DamageSource source) {
		int pieces = cinderPieces(victim);
		if (pieces > 0 && source.getAttacker() instanceof LivingEntity attacker && attacker != victim && !attacker.isFireImmune()) {
			attacker.setOnFireFor(CINDER_SECONDS * pieces);
		}
	}

	// ------------------------------------------------------------------ worn effects

	/** True when every armour slot holds a vestment. */
	public static boolean wearsFullSet(LivingEntity e) {
		return isVestment(e.getEquippedStack(EquipmentSlot.HEAD)) && isVestment(e.getEquippedStack(EquipmentSlot.CHEST))
				&& isVestment(e.getEquippedStack(EquipmentSlot.LEGS)) && isVestment(e.getEquippedStack(EquipmentSlot.FEET));
	}

	public static boolean wears(LivingEntity e, EquipmentSlot slot) {
		return isVestment(e.getEquippedStack(slot));
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient) {
			return;
		}
		prepare(stack);
		if (!(entity instanceof LivingEntity wearer) || wearer.getEquippedStack(getSlotType()) != stack) {
			return;   // only while worn
		}
		wornTick(stack, (ServerWorld) world, wearer);
	}

	/** Also called by the NPC for its own pieces (mobs do not tick their armour). */
	public void wornTick(ItemStack stack, ServerWorld world, LivingEntity wearer) {
		long t = world.getTime();
		switch (getSlotType()) {
			case HEAD -> {
				if (t % 10 == 0) {
					wearer.removeStatusEffect(StatusEffects.BLINDNESS);
					wearer.removeStatusEffect(StatusEffects.DARKNESS);
					wearer.removeStatusEffect(StatusEffects.NAUSEA);
				}
			}
			case CHEST -> {
				if (t % 10 == 0 && !wearer.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
					wearer.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 30, 0, true, false, false));
				}
				if (t % WARD_PERIOD_TICKS == 0 && wearer.getAbsorptionAmount() <= 0) {
					wearer.setAbsorptionAmount(WARD_ABSORPTION);
					world.spawnParticles(ParticleTypes.END_ROD, wearer.getX(), wearer.getBodyY(0.7), wearer.getZ(), 6, 0.4, 0.4, 0.4, 0.02);
				}
				if (wearsFullSet(wearer) && wearer.getHealth() <= wearer.getMaxHealth() * NOVA_HEALTH_FRACTION) {
					nova(stack, world, wearer);
				}
			}
			case LEGS -> {
				if (t % 10 == 0) {
					wearer.removeStatusEffect(StatusEffects.SLOWNESS);
				}
			}
			default -> {
			}
		}
	}

	/** The Envoy's Light: heal, regenerate, hurl and mark everything hostile around. Once per cooldown. */
	public static boolean nova(ItemStack chest, ServerWorld world, LivingEntity wearer) {
		NbtCompound nbt = chest.getOrCreateNbt();
		long t = world.getTime();
		if (nbt.contains(NBT_NOVA_AT) && t - nbt.getLong(NBT_NOVA_AT) < NOVA_COOLDOWN_TICKS) {
			return false;
		}
		nbt.putLong(NBT_NOVA_AT, t);
		wearer.heal(NOVA_HEAL);
		wearer.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1, false, false, true));
		Box area = Box.of(wearer.getPos(), NOVA_RADIUS * 2, NOVA_RADIUS, NOVA_RADIUS * 2);
		for (HostileEntity e : world.getEntitiesByClass(HostileEntity.class, area, h -> h.isAlive() && h.getPos().distanceTo(wearer.getPos()) <= NOVA_RADIUS)) {
			Vec3d away = e.getPos().subtract(wearer.getPos()).normalize();
			e.takeKnockback(2.0, -away.x, -away.z);
			e.velocityModified = true;
			e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0, false, false, true));
			e.damage(world.getDamageSources().indirectMagic(wearer, wearer), 4.0f);
		}
		for (int i = 0; i < 24; i++) {
			double a = i * Math.PI / 12;
			world.spawnParticles(ParticleTypes.END_ROD, wearer.getX() + Math.cos(a) * 2, wearer.getBodyY(0.5), wearer.getZ() + Math.sin(a) * 2, 1, Math.cos(a) * 0.3, 0.1, Math.sin(a) * 0.3, 0.2);
		}
		world.playSound(null, wearer.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
		return true;
	}

	/** Boots: fall damage is refused (hooked from {@code ServerLivingEntityEvents.ALLOW_DAMAGE}). */
	public static boolean refusesFall(LivingEntity e) {
		return wears(e, EquipmentSlot.FEET);
	}

	// ------------------------------------------------------------------ plumbing

	@Override
	public boolean isDamageable() {
		return false;
	}


	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}

	@Override
	public boolean isEnchantable(ItemStack stack) {
		return false;
	}

	@Override
	public Text getName(ItemStack stack) {
		return HolySwordItem.shimmer(net.minecraft.util.Language.getInstance().get(getTranslationKey(stack)));
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		String k = "item.luminary.vestments.";
		String piece = getSlotType().getName();
		tooltip.add(Text.translatable("item.luminary.rarity.godlike").styled(st -> st.withColor(HolySwordItem.GODLIKE_COLOR).withBold(true)));
		HolySwordItem.ownerLine(stack).ifPresent(tooltip::add);
		tooltip.add(Text.translatable(k + "lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + piece + ".param").formatted(Formatting.BLUE));
		tooltip.add(Text.translatable(k + piece + ".effect.title").formatted(Formatting.GOLD));
		tooltip.add(Text.translatable(k + piece + ".effect.text").formatted(Formatting.GRAY));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + "set.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable(k + "set.text", (int) (NOVA_HEALTH_FRACTION * 100), (int) NOVA_HEAL, (int) NOVA_RADIUS).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + NOVA_COOLDOWN_TICKS / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.empty());
		NbtCompound nbt = stack.getNbt();
		String rune = nbt == null ? "" : nbt.getString(NBT_RUNE);
		String nether = nbt == null ? "" : nbt.getString(NBT_NETHER_RUNE);
		tooltip.add((rune.isEmpty() ? Text.translatable("item.luminary.holy_sword_of_lumin.socket.rune.empty") : Text.translatable(k + "rune." + rune)).formatted(Formatting.LIGHT_PURPLE));
		if (!rune.isEmpty()) {
			tooltip.add(Text.translatable(k + "rune." + rune + ".text", (int) DAWN_HEALTH).formatted(Formatting.DARK_GRAY));
		}
		tooltip.add((nether.isEmpty() ? Text.translatable("item.luminary.holy_sword_of_lumin.socket.nether.empty") : Text.translatable(k + "rune." + nether)).formatted(Formatting.RED));
		if (!nether.isEmpty()) {
			tooltip.add(Text.translatable(k + "rune." + nether + ".text", CINDER_SECONDS).formatted(Formatting.DARK_GRAY));
		}
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(isBound(stack) ? k + "bound" : k + "unbound").formatted(Formatting.DARK_PURPLE));
	}
}
