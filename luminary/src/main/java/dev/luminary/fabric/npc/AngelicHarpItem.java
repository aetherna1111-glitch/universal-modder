package dev.luminary.fabric.npc;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
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
 * The Angelic Harp: the weapon Lumin takes up after his blade is given away for good. Built like the Holy Sword
 * (the same tier, the same two runes, the same blessings, 15 damage at 1.7 swings a second) with its own voice:
 * <ul>
 * <li><b>Resonance</b> (the Witness of the sword): every hit adds a note, up to {@value #MAX_NOTES}, +0.5 damage each;
 * notes fade when the harp rests.</li>
 * <li><b>Hymn</b> (the Revelation): a tenth of the hits sound a hymn: two notes and a breath of healing for the bearer.</li>
 * <li><b>Holy damage to the marked</b>: a quarter more, ignoring armour, on anything already glowing.</li>
 * <li><b>Choir of the Catalog</b> (the Light): while held, every 2 s allies within 4 blocks are healed a little and
 * hostiles are marked.</li>
 * <li><b>Right-click, the Chord of Light</b> (the Verdict): spends all notes: monsters within 6 blocks take
 * 3 + 2 per note holy damage (the undead twice), are slowed and lit; allies are healed.</li>
 * <li><b>Shift + right-click, the Lullaby</b> (the Sanctuary, 3 notes): hostiles within 5 blocks forget their
 * target, are slowed and weakened for 8 s.</li>
 * </ul>
 * Lumin's own harp carries his name and never leaves his hands; a free harp comes from the creative tab.
 */
public final class AngelicHarpItem extends Item {
	public static final String NBT_NOTES = "LuminaryNotes";
	public static final String NBT_LAST_NOTE = "LuminaryLastNote";
	public static final int MAX_NOTES = 7;
	public static final double BASE_DAMAGE = HolySwordItem.BASE_DAMAGE;
	public static final double DAMAGE_PER_NOTE = 0.5;
	public static final double ATTACK_SPEED = HolySwordItem.ATTACK_SPEED;
	public static final double HYMN_CHANCE = 0.10;
	public static final double MARKED_BONUS = 0.25;
	public static final double CHOIR_RADIUS = 4.0;
	public static final int CHOIR_PERIOD_TICKS = 40;
	public static final float CHOIR_HEAL = 1.0f;
	public static final double CHORD_RADIUS = 6.0;
	public static final float CHORD_BASE = 3.0f;
	public static final float CHORD_PER_NOTE = 2.0f;
	public static final float CHORD_HEAL = 4.0f;
	public static final int CHORD_COOLDOWN = 120;
	public static final int LULLABY_COST = 3;
	public static final double LULLABY_RADIUS = 5.0;
	public static final int LULLABY_TICKS = 160;
	public static final int LULLABY_COOLDOWN = 600;
	public static final int NOTE_FADE_TICKS = 600;
	private static final UUID DAMAGE_MODIFIER_ID = UUID.fromString("5b0a6e2c-4c3f-4d8e-9a1b-2f6d7c8e9f01");
	private static final UUID SPEED_MODIFIER_ID = UUID.fromString("5b0a6e2c-4c3f-4d8e-9a1b-2f6d7c8e9f02");
	private static final UUID LUCK_MODIFIER_ID = UUID.fromString("5b0a6e2c-4c3f-4d8e-9a1b-2f6d7c8e9f03");

	public AngelicHarpItem(Settings settings) {
		super(settings);
	}

	// ------------------------------------------------------------------ identity

	/** Lumin's harp: unbreakable, named after him, fully prepared. */
	public static ItemStack bound(Item item, LuminaryId npc, String ownerNameKey) {
		ItemStack stack = new ItemStack(item);
		stack.getOrCreateNbt().putString(HolySwordItem.NBT_OWNER, npc.toString());
		prepare(stack);
		return HolySwordItem.withOwnerName(stack, ownerNameKey);
	}

	/** A free harp (creative tab): every ability, both runes, the blessings. */
	public static ItemStack unbound(Item item) {
		ItemStack stack = new ItemStack(item);
		prepare(stack);
		return stack;
	}

	/** Runes, attributes and blessings; idempotent. */
	public static void prepare(ItemStack stack) {
		NbtCompound nbt = stack.getOrCreateNbt();
		if (nbt.getBoolean(HolySwordItem.NBT_PREPARED)) {
			return;
		}
		nbt.putBoolean(HolySwordItem.NBT_PREPARED, true);
		nbt.putBoolean("Unbreakable", true);
		nbt.putString(HolySwordItem.NBT_RUNE, HolySwordItem.RUNE_CLARITY);
		nbt.putString(HolySwordItem.NBT_NETHER_RUNE, HolySwordItem.RUNE_EMBERS);
		writeAttributes(stack, notes(stack));
		HolySwordItem.bless(stack);
	}

	public static int notes(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt == null ? 0 : Math.max(0, Math.min(MAX_NOTES, nbt.getInt(NBT_NOTES)));
	}

	public static void setNotes(ItemStack stack, int count, long now) {
		int clamped = Math.max(0, Math.min(MAX_NOTES, count));
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putInt(NBT_NOTES, clamped);
		nbt.putLong(NBT_LAST_NOTE, now);
		writeAttributes(stack, clamped);
	}

	static void writeAttributes(ItemStack stack, int notes) {
		stack.getOrCreateNbt().remove("AttributeModifiers");
		stack.addAttributeModifier(EntityAttributes.GENERIC_ATTACK_DAMAGE,
				new EntityAttributeModifier(DAMAGE_MODIFIER_ID, "Weapon modifier", BASE_DAMAGE - 1.0 + notes * DAMAGE_PER_NOTE, EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
		stack.addAttributeModifier(EntityAttributes.GENERIC_ATTACK_SPEED,
				new EntityAttributeModifier(SPEED_MODIFIER_ID, "Weapon modifier", ATTACK_SPEED, EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
		if (HolySwordItem.hasRune(stack, HolySwordItem.RUNE_CLARITY)) {
			stack.addAttributeModifier(EntityAttributes.GENERIC_LUCK,
					new EntityAttributeModifier(LUCK_MODIFIER_ID, "Rune of Clarity", 1.0, EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
		}
	}

	public static float holyBonus(ItemStack stack) {
		return (float) ((BASE_DAMAGE + notes(stack) * DAMAGE_PER_NOTE) * MARKED_BONUS);
	}

	static int fadeTicks(ItemStack stack) {
		return HolySwordItem.hasRune(stack, HolySwordItem.RUNE_CLARITY) ? NOTE_FADE_TICKS * 2 : NOTE_FADE_TICKS;
	}

	/** A plain {@code /give} harp has no modifiers yet: the base values until the first inventory tick prepares it. */
	@Override
	public Multimap<EntityAttribute, EntityAttributeModifier> getAttributeModifiers(EquipmentSlot slot) {
		if (slot != EquipmentSlot.MAINHAND) {
			return super.getAttributeModifiers(slot);
		}
		return ImmutableMultimap.of(
				EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(DAMAGE_MODIFIER_ID, "Weapon modifier", BASE_DAMAGE - 1.0, EntityAttributeModifier.Operation.ADDITION),
				EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(SPEED_MODIFIER_ID, "Weapon modifier", ATTACK_SPEED, EntityAttributeModifier.Operation.ADDITION));
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}

	@Override
	public boolean isDamageable() {
		return false;
	}

	@Override
	public Text getName(ItemStack stack) {
		return HolySwordItem.shimmer(net.minecraft.util.Language.getInstance().get(getTranslationKey(stack)));
	}

	// ------------------------------------------------------------------ hits

	@Override
	public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		if (!(attacker.getWorld() instanceof ServerWorld sw)) {
			return true;
		}
		boolean marked = target.hasStatusEffect(StatusEffects.GLOWING);
		boolean burning = target.isOnFire();
		boolean hymn = sw.getRandom().nextDouble() < HYMN_CHANCE;
		setNotes(stack, notes(stack) + (hymn ? 2 : 1), sw.getTime());
		if (hymn) {
			attacker.heal(1.0f);
			sw.playSound(null, attacker.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.8f, 1.5f);
			sw.spawnParticles(ParticleTypes.END_ROD, attacker.getX(), attacker.getBodyY(0.8), attacker.getZ(), 10, 0.4, 0.5, 0.4, 0.05);
		}
		if (marked && target.isAlive()) {
			int regen = target.timeUntilRegen;
			target.timeUntilRegen = 0;
			target.damage(sw.getDamageSources().indirectMagic(attacker, attacker), holyBonus(stack));
			target.timeUntilRegen = Math.max(regen, target.timeUntilRegen);
		}
		if (HolySwordItem.hasRune(stack, HolySwordItem.RUNE_EMBERS)) {
			target.setOnFireFor(4);
			if (burning) {
				attacker.heal(1.0f);
			}
		}
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 4, 0));
		sw.spawnParticles(ParticleTypes.NOTE, target.getX(), target.getBodyY(0.9), target.getZ(), 3, 0.3, 0.3, 0.3, 1.0);
		return true;
	}

	// ------------------------------------------------------------------ abilities

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) {
			return TypedActionResult.success(stack);
		}
		boolean done = user.isSneaking() ? lullaby(stack, (ServerWorld) world, user) : chord(stack, (ServerWorld) world, user, null);
		if (done) {
			user.swingHand(hand);
		}
		return done ? TypedActionResult.success(stack) : TypedActionResult.fail(stack);
	}

	/**
	 * The Chord of Light. A player spends every note ({@code fixedDamage} null; refused with none, or on cooldown);
	 * the NPC plays with a fixed strength. Returns false when nothing was played.
	 */
	public static boolean chord(ItemStack stack, ServerWorld world, LivingEntity player, Float fixedDamage) {
		int n = notes(stack);
		float damage;
		if (fixedDamage == null) {
			if (!(player instanceof PlayerEntity p) || p.getItemCooldownManager().isCoolingDown(stack.getItem()) || n == 0) {
				return false;
			}
			damage = CHORD_BASE + CHORD_PER_NOTE * n;
			setNotes(stack, 0, world.getTime());
			p.getItemCooldownManager().set(stack.getItem(), CHORD_COOLDOWN);
		} else {
			damage = fixedDamage;
		}
		Box box = player.getBoundingBox().expand(CHORD_RADIUS);
		for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box, le -> le != player && le.squaredDistanceTo(player) <= CHORD_RADIUS * CHORD_RADIUS)) {
			if (e instanceof PlayerEntity || e instanceof LuminaryNpcEntity) {
				e.heal(CHORD_HEAL);
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 6, 0));
			} else if (e instanceof HostileEntity) {
				e.timeUntilRegen = 0;
				e.damage(world.getDamageSources().indirectMagic(player, player), e.isUndead() ? damage * 2 : damage);
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 5, 1));
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 6, 0));
			}
		}
		player.heal(CHORD_HEAL * 0.5f);
		for (float pitch : new float[] {0.8f, 1.0f, 1.26f, 1.5f}) {
			world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 1.0f, pitch);
		}
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.9f);
		world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 60, CHORD_RADIUS * 0.6, 1.0, CHORD_RADIUS * 0.6, 0.03);
		world.spawnParticles(ParticleTypes.NOTE, player.getX(), player.getY() + 2.2, player.getZ(), 8, 0.6, 0.3, 0.6, 1.0);
		return true;
	}

	/** The Lullaby: three notes; hostiles within 5 blocks drop their target, slowed and weakened for 8 s. */
	public static boolean lullaby(ItemStack stack, ServerWorld world, PlayerEntity user) {
		if (user.getItemCooldownManager().isCoolingDown(stack.getItem()) || notes(stack) < LULLABY_COST) {
			return false;
		}
		setNotes(stack, notes(stack) - LULLABY_COST, world.getTime());
		user.getItemCooldownManager().set(stack.getItem(), LULLABY_COOLDOWN);
		Box box = user.getBoundingBox().expand(LULLABY_RADIUS);
		for (HostileEntity e : world.getEntitiesByClass(HostileEntity.class, box, h -> h.isAlive() && h.squaredDistanceTo(user) <= LULLABY_RADIUS * LULLABY_RADIUS)) {
			e.setTarget(null);
			e.getNavigation().stop();
			e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, LULLABY_TICKS, 3));
			e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, LULLABY_TICKS, 1));
		}
		for (float pitch : new float[] {1.2f, 1.0f, 0.8f}) {
			world.playSound(null, user.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.7f, pitch);
		}
		world.spawnParticles(ParticleTypes.NOTE, user.getX(), user.getY() + 1.5, user.getZ(), 16, LULLABY_RADIUS * 0.5, 0.5, LULLABY_RADIUS * 0.5, 1.0);
		return true;
	}

	/** The unique effect: the Choir of the Catalog. Allies near the bearer are healed a little, hostiles marked. */
	public static int choir(ServerWorld world, LivingEntity bearer) {
		int touched = 0;
		Box area = Box.of(bearer.getPos(), CHOIR_RADIUS * 2, CHOIR_RADIUS, CHOIR_RADIUS * 2);
		for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, le -> le.isAlive() && le.getPos().distanceTo(bearer.getPos()) <= CHOIR_RADIUS)) {
			if (e instanceof HostileEntity) {
				e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, CHOIR_PERIOD_TICKS + 20, 0, true, false, false));
				touched++;
			} else if (e instanceof PlayerEntity || e instanceof LuminaryNpcEntity) {
				e.heal(CHOIR_HEAL);
			}
		}
		for (int i = 0; i < 6; i++) {
			double a = (world.getTime() / 8.0 + i) * Math.PI / 3;
			world.spawnParticles(ParticleTypes.END_ROD, bearer.getX() + Math.cos(a) * CHOIR_RADIUS, bearer.getY() + 0.3, bearer.getZ() + Math.sin(a) * CHOIR_RADIUS, 1, 0, 0.02, 0, 0.0);
		}
		return touched;
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient) {
			return;
		}
		prepare(stack);
		if (world.getTime() % 20 != 0) {
			return;
		}
		if (entity instanceof PlayerEntity player && selected && world.getTime() % CHOIR_PERIOD_TICKS == 0 && world instanceof ServerWorld sw) {
			choir(sw, player);
		}
		int n = notes(stack);
		NbtCompound nbt = stack.getNbt();
		if (n > 0 && nbt != null && world.getTime() - nbt.getLong(NBT_LAST_NOTE) >= fadeTicks(stack)) {
			setNotes(stack, n - 1, world.getTime());
		}
	}

	// ------------------------------------------------------------------ tooltip, the sword's layout

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		String k = "item.luminary.angelic_harp.";
		String s = "item.luminary.holy_sword_of_lumin.";
		tooltip.add(Text.translatable("item.luminary.rarity.godlike").styled(st -> st.withColor(HolySwordItem.GODLIKE_COLOR).withBold(true)));
		HolySwordItem.ownerLine(stack).ifPresent(tooltip::add);
		tooltip.add(Text.translatable(k + "lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.translatable(k + "lore2").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + "param.hymn", String.format(java.util.Locale.ROOT, "%.2f", HYMN_CHANCE)).formatted(Formatting.BLUE));
		tooltip.add(Text.translatable(k + "param.marked", (int) Math.round(MARKED_BONUS * 100)).formatted(Formatting.BLUE));
		int n = notes(stack);
		tooltip.add(Text.translatable(k + "resonance", n, MAX_NOTES).formatted(Formatting.AQUA));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + "effect.title").formatted(Formatting.GOLD));
		tooltip.add(Text.translatable(k + "effect.text", (int) CHOIR_RADIUS, CHOIR_PERIOD_TICKS / 20).formatted(Formatting.GRAY));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(s + "use.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable(k + "chord", (int) CHORD_RADIUS, (int) (CHORD_BASE + CHORD_PER_NOTE * n)).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + CHORD_COOLDOWN / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.translatable(s + "sneak.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable(k + "lullaby", LULLABY_COST, (int) LULLABY_RADIUS).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + LULLABY_COOLDOWN / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.empty());
		NbtCompound nbt = stack.getNbt();
		String rune = nbt == null ? "" : nbt.getString(HolySwordItem.NBT_RUNE);
		String nether = nbt == null ? "" : nbt.getString(HolySwordItem.NBT_NETHER_RUNE);
		tooltip.add((rune.isEmpty() ? Text.translatable(s + "socket.rune.empty") : Text.translatable(s + "rune." + rune)).formatted(Formatting.LIGHT_PURPLE));
		if (!rune.isEmpty()) {
			tooltip.add(Text.translatable(k + "rune." + rune + ".text").formatted(Formatting.DARK_GRAY));
		}
		tooltip.add((nether.isEmpty() ? Text.translatable(s + "socket.nether.empty") : Text.translatable(s + "rune." + nether)).formatted(Formatting.RED));
		if (!nether.isEmpty()) {
			tooltip.add(Text.translatable(s + "rune." + nether + ".text").formatted(Formatting.DARK_GRAY));
		}
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(HolySwordItem.owner(stack).isPresent() ? k + "bound" : k + "unbound").formatted(Formatting.DARK_PURPLE));
	}
}
