package dev.luminary.fabric.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.fabric.LuminaryRuntime;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Holy Sword of Lumin: the unique weapon of the Ascending Intelligence. Indestructible, bound to its NPC and to a
 * custody instance id (a stack with another id is a stale copy and vanishes in a player's hands, see
 * {@code BehaviourService#reconcileHeldBlade}), and alive in its own way:
 * <ul>
 *   <li><b>Witness</b> (passive): every hit records evidence, up to {@value #MAX_EVIDENCE} marks; each mark adds
 *       {@value #DAMAGE_PER_EVIDENCE} attack damage (written into the stack's attribute modifiers, so the tooltip and
 *       vanilla damage see it). Evidence fades a mark at a time after {@value #EVIDENCE_FADE_TICKS} ticks without a hit.</li>
 *   <li><b>Verdict</b> (use): spends all evidence in a {@value #VERDICT_RANGE}-block beam of light through everything in
 *       line: {@value #VERDICT_BASE} + {@value #VERDICT_PER_EVIDENCE} × marks damage, undead take half again and burn,
 *       everything struck glows for ten seconds. Cooldown {@value #VERDICT_COOLDOWN} ticks.</li>
 *   <li><b>Sanctuary</b> (sneak + use, needs {@value #SANCTUARY_COST} marks): plants a circle of light for
 *       {@value #SANCTUARY_TICKS} ticks: players inside regenerate and resist, hostile mobs inside are weakened and
 *       glow. Cooldown {@value #SANCTUARY_COOLDOWN} ticks.</li>
 * </ul>
 * In Lumin's own hands the sword is the half-health blow of self-defence; beyond reach he judges with the beam.
 */
public final class HolySwordItem extends Item {
	public static final String NBT_OWNER = "LuminaryOwner";
	public static final String NBT_INSTANCE = "LuminaryInstance";
	public static final String NBT_EVIDENCE = "LuminaryEvidence";
	public static final String NBT_LAST_HIT = "LuminaryLastHit";

	public static final int MAX_EVIDENCE = 7;
	public static final double BASE_DAMAGE = 15.0;     // shown as 15 attack damage with an empty ledger
	public static final double DAMAGE_PER_EVIDENCE = 0.5;
	public static final double ATTACK_SPEED = -2.3;    // 1.7 swings per second
	/** The sword's own rarity tier, above the pack's mythic: the name is drawn in this colour, bold, and the tooltip names the tier. */
	public static final int GODLIKE_COLOR = 0xFFE27A;

	/** Unique parameters (the blue "+x" lines): chance per hit of a Revelation, bonus holy damage against marked (glowing) targets. */
	public static final double REVELATION_CHANCE = 0.10;
	public static final double MARKED_BONUS = 0.25;
	/** Unique effect: the Light of the Catalog, an aura around the bearer while the sword is in hand. */
	public static final double AURA_RADIUS = 4.0;
	public static final int AURA_PERIOD_TICKS = 40;
	public static final float AURA_DAMAGE = 1.0f;
	/** Runes in the two sockets. Clarity (runic): +1 luck and marks fade twice as slowly. Embers (netherfused): hits ignite, hitting a burning target heals. */
	public static final String NBT_RUNE = "LuminaryRune";
	public static final String NBT_NETHER_RUNE = "LuminaryNetherRune";
	public static final String RUNE_CLARITY = "clarity";
	public static final String RUNE_EMBERS = "embers";
	private static final UUID LUCK_MODIFIER_ID = UUID.fromString("6d1a0f3e-4f0b-4d0a-9c7e-2e5b7a1c9d11");
	public static final int EVIDENCE_FADE_TICKS = 600;
	public static final double VERDICT_RANGE = 12.0;
	public static final float VERDICT_BASE = 3.0f;
	public static final float VERDICT_PER_EVIDENCE = 2.0f;
	public static final int VERDICT_COOLDOWN = 120;
	public static final int SANCTUARY_COST = 3;
	public static final int SANCTUARY_TICKS = 160;
	public static final double SANCTUARY_RADIUS = 5.0;
	public static final int SANCTUARY_COOLDOWN = 600;

	/**
	 * The blessings: enchantments written onto every sword the custody mints, as {@code namespace:path:level}. Ids of
	 * mods that are not installed are skipped silently, so the same list serves a bare server and the full pack
	 * (vanilla, MC Dungeons Weapons, Zenith). Configurable through {@code weapon.blessings}.
	 */
	public static final List<String> DEFAULT_BLESSINGS = List.of(
			"minecraft:sharpness:6", "minecraft:smite:7", "minecraft:looting:4", "minecraft:sweeping:3", "minecraft:fire_aspect:2", "minecraft:knockback:1",
			"mcdw:smiting:3", "mcdw:radiance:3", "mcdw:thundering:3", "mcdw:committed:3", "mcdw:phantoms_mark:3", "mcdw:leeching:3",
			"mcdw:swirling:3", "mcdw:guarding_strike:3", "mcdw:echo:2",
			"zenith:scavenger:3", "zenith:capturing:3");
	private static volatile List<String> blessings = DEFAULT_BLESSINGS;

	public static void setBlessings(List<String> list) {
		blessings = List.copyOf(list);
	}

	public static List<String> blessings() {
		return blessings;
	}

	/** Writes the configured blessings onto the stack; returns how many were applied (installed mods only). */
	public static int bless(ItemStack stack) {
		return blessWith(stack, blessings);
	}

	public static int blessWith(ItemStack stack, List<String> specs) {
		int applied = 0;
		for (String spec : specs) {
			int colon = spec.lastIndexOf(':');
			if (colon <= 0) {
				continue;
			}
			try {
				net.minecraft.util.Identifier id = new net.minecraft.util.Identifier(spec.substring(0, colon));
				int level = Integer.parseInt(spec.substring(colon + 1).trim());
				Optional<net.minecraft.enchantment.Enchantment> ench = net.minecraft.registry.Registries.ENCHANTMENT.getOrEmpty(id);
				if (ench.isPresent() && level > 0) {
					stack.addEnchantment(ench.get(), level);
					applied++;
				}
			} catch (RuntimeException ignored) {
				// a malformed entry never breaks the sword
			}
		}
		return applied;
	}

	public HolySwordItem(Settings settings) {
		super(settings);
	}

	// ------------------------------------------------------------------ identity

	public static final String NBT_PREPARED = "LuminaryPrepared";
	/** The owner's name key (a lang key), so the tooltip can say whose it is in the viewer's language. */
	public static final String NBT_OWNER_NAME = "LuminaryOwnerName";

	public static ItemStack withOwnerName(ItemStack stack, String nameKey) {
		if (nameKey != null && !nameKey.isEmpty()) {
			stack.getOrCreateNbt().putString(NBT_OWNER_NAME, nameKey);
		}
		return stack;
	}

	public static Optional<String> ownerNameKey(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.contains(NBT_OWNER_NAME) ? Optional.of(nbt.getString(NBT_OWNER_NAME)) : Optional.empty();
	}

	/** "Belongs to Lumin" in gold, or nothing for a free item. */
	public static Optional<Text> ownerLine(ItemStack stack) {
		return ownerNameKey(stack).map(k -> Text.translatable("item.luminary.owner", Text.translatable(k)).formatted(Formatting.GOLD));
	}

	/** A stack bound to {@code npc}'s current custody instance, with its base attributes written. */
	public static ItemStack bound(Item item, LuminaryId npc, UUID instance) {
		ItemStack stack = new ItemStack(item);
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putString(NBT_OWNER, npc.toString());
		nbt.putUuid(NBT_INSTANCE, instance);
		prepare(stack);
		return stack;
	}

	/**
	 * A free blade: the creative tab and {@code /give} hand out swords that belong to nobody. They have every ability,
	 * both runes and the blessings, but no custody: they never fly home and are never purged.
	 */
	public static ItemStack unbound(Item item) {
		ItemStack stack = new ItemStack(item);
		prepare(stack);
		return stack;
	}

	/** Runes, attributes and blessings; idempotent (a plain {@code /give} stack is prepared on its first inventory tick). */
	public static void prepare(ItemStack stack) {
		NbtCompound nbt = stack.getOrCreateNbt();
		if (nbt.getBoolean(NBT_PREPARED)) {
			return;
		}
		nbt.putBoolean(NBT_PREPARED, true);
		nbt.putString(NBT_RUNE, RUNE_CLARITY);
		nbt.putString(NBT_NETHER_RUNE, RUNE_EMBERS);
		writeAttributes(stack, evidence(stack));
		bless(stack);
	}

	public static boolean isBound(ItemStack stack) {
		return owner(stack).isPresent() && instance(stack).isPresent();
	}

	public static Optional<LuminaryId> owner(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		if (nbt == null || !nbt.contains(NBT_OWNER)) {
			return Optional.empty();
		}
		try {
			return Optional.of(LuminaryId.parse(nbt.getString(NBT_OWNER)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public static Optional<UUID> instance(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.containsUuid(NBT_INSTANCE) ? Optional.of(nbt.getUuid(NBT_INSTANCE)) : Optional.empty();
	}

	// ------------------------------------------------------------------ evidence

	public static int evidence(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt == null ? 0 : Math.max(0, Math.min(MAX_EVIDENCE, nbt.getInt(NBT_EVIDENCE)));
	}

	public static void setEvidence(ItemStack stack, int marks, long now) {
		NbtCompound nbt = stack.getOrCreateNbt();
		int clamped = Math.max(0, Math.min(MAX_EVIDENCE, marks));
		nbt.putInt(NBT_EVIDENCE, clamped);
		nbt.putLong(NBT_LAST_HIT, now);
		writeAttributes(stack, clamped);
	}

	/** Attack damage and speed as vanilla item attribute modifiers on the stack, so the game and the tooltip agree. */
	static void writeAttributes(ItemStack stack, int marks) {
		stack.getOrCreateNbt().remove("AttributeModifiers");
		stack.addAttributeModifier(EntityAttributes.GENERIC_ATTACK_DAMAGE,
				new EntityAttributeModifier(ATTACK_DAMAGE_MODIFIER_ID, "Weapon modifier", BASE_DAMAGE - 1.0 + marks * DAMAGE_PER_EVIDENCE, EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
		stack.addAttributeModifier(EntityAttributes.GENERIC_ATTACK_SPEED,
				new EntityAttributeModifier(ATTACK_SPEED_MODIFIER_ID, "Weapon modifier", ATTACK_SPEED, EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
		if (hasRune(stack, RUNE_CLARITY)) {
			stack.addAttributeModifier(EntityAttributes.GENERIC_LUCK,
					new EntityAttributeModifier(LUCK_MODIFIER_ID, "Rune of Clarity", 1.0, EntityAttributeModifier.Operation.ADDITION), EquipmentSlot.MAINHAND);
		}
	}

	/** The holy follow-up on a marked target: a quarter of the sword's current damage. */
	public static float holyBonus(ItemStack stack) {
		return (float) ((BASE_DAMAGE + evidence(stack) * DAMAGE_PER_EVIDENCE) * MARKED_BONUS);
	}

	public static boolean hasRune(ItemStack stack, String rune) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && (rune.equals(nbt.getString(NBT_RUNE)) || rune.equals(nbt.getString(NBT_NETHER_RUNE)));
	}

	static int fadeTicks(ItemStack stack) {
		return hasRune(stack, RUNE_CLARITY) ? EVIDENCE_FADE_TICKS * 2 : EVIDENCE_FADE_TICKS;
	}

	@Override
	public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		if (attacker.getWorld().isClient || !(attacker.getWorld() instanceof ServerWorld sw)) {
			return true;
		}
		boolean marked = target.hasStatusEffect(StatusEffects.GLOWING);
		boolean burning = target.isOnFire();
		// Revelation: a tenth of the hits reveal more than they should: two marks and a breath of light for the bearer
		boolean revelation = sw.getRandom().nextDouble() < REVELATION_CHANCE;
		setEvidence(stack, evidence(stack) + (revelation ? 2 : 1), sw.getTime());
		if (revelation) {
			attacker.heal(1.0f);
			sw.spawnParticles(ParticleTypes.END_ROD, attacker.getX(), attacker.getBodyY(0.8), attacker.getZ(), 10, 0.4, 0.5, 0.4, 0.05);
		}
		// Judgement of the marked: a quarter of the sword's damage more, as holy damage that armour does not stop, on anything already glowing
		if (marked && target.isAlive()) {
			float extra = holyBonus(stack);
			int regen = target.timeUntilRegen;
			target.timeUntilRegen = 0;   // the follow-up must not be swallowed by the invulnerability frames of the hit itself
			target.damage(sw.getDamageSources().indirectMagic(attacker, attacker), extra);
			target.timeUntilRegen = Math.max(regen, target.timeUntilRegen);
		}
		// Rune of Embers: every hit kindles; a hit on a burning target feeds the bearer
		if (hasRune(stack, RUNE_EMBERS)) {
			target.setOnFireFor(4);
			if (burning) {
				attacker.heal(1.0f);
			}
		}
		sw.spawnParticles(ParticleTypes.END_ROD, target.getX(), target.getBodyY(0.6), target.getZ(), 4, 0.3, 0.3, 0.3, 0.02);
		return true;
	}

	// ------------------------------------------------------------------ abilities

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) {
			return TypedActionResult.success(stack);
		}
		boolean done = user.isSneaking() ? sanctuary(stack, (ServerWorld) world, user) : verdict(stack, (ServerWorld) world, user, user.getEyePos(), user.getRotationVec(1.0f), null);
		return done ? TypedActionResult.success(stack) : TypedActionResult.fail(stack);
	}

	/**
	 * The beam. {@code attacker} is the entity wielding the sword (a player or the NPC); damage is attributed to it.
	 * Returns false on cooldown or with no evidence (a player) / never for the NPC, which judges with a fixed strength.
	 */
	public static boolean verdict(ItemStack stack, ServerWorld world, LivingEntity attacker, Vec3d from, Vec3d direction, Float fixedDamage) {
		int marks = evidence(stack);
		if (attacker instanceof PlayerEntity p) {
			if (p.getItemCooldownManager().isCoolingDown(stack.getItem()) || marks == 0) {
				return false;
			}
			p.getItemCooldownManager().set(stack.getItem(), VERDICT_COOLDOWN);
		}
		float damage = fixedDamage != null ? fixedDamage : VERDICT_BASE + VERDICT_PER_EVIDENCE * marks;
		Vec3d to = from.add(direction.normalize().multiply(VERDICT_RANGE));
		Box sweep = new Box(from, to).expand(0.5);
		List<LivingEntity> struck = new ArrayList<>();
		for (Entity e : world.getOtherEntities(attacker, sweep, e -> e instanceof LivingEntity l && l.isAlive() && !l.isSpectator())) {
			if (e.getBoundingBox().expand(0.3).raycast(from, to).isPresent()) {
				struck.add((LivingEntity) e);
			}
		}
		for (LivingEntity target : struck) {
			float dealt = target.isUndead() ? damage * 1.5f : damage;
			target.damage(attacker instanceof PlayerEntity p ? world.getDamageSources().playerAttack(p) : world.getDamageSources().mobAttack(attacker), dealt);
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0, false, false, true));
			if (target.isUndead()) {
				target.setOnFireFor(4);
			}
		}
		for (double d = 0.5; d < VERDICT_RANGE; d += 0.5) {
			Vec3d p = from.add(direction.normalize().multiply(d));
			world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
		}
		world.playSound(null, attacker.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.4f);
		if (attacker instanceof PlayerEntity) {
			setEvidence(stack, 0, world.getTime());
		}
		return true;
	}

	static boolean sanctuary(ItemStack stack, ServerWorld world, PlayerEntity user) {
		if (user.getItemCooldownManager().isCoolingDown(stack.getItem()) || evidence(stack) < SANCTUARY_COST) {
			return false;
		}
		user.getItemCooldownManager().set(stack.getItem(), SANCTUARY_COOLDOWN);
		setEvidence(stack, evidence(stack) - SANCTUARY_COST, world.getTime());
		LuminaryRuntime.get().ifPresent(rt -> rt.npc().behaviour().sanctuaries().plant(world, user.getPos(), world.getTime() + SANCTUARY_TICKS));
		world.playSound(null, user.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.2f);
		return true;
	}

	/** The unique effect: the Light of the Catalog. Hostile things near the bearer are hurt a little and marked; undead twice. */
	public static int aura(ServerWorld world, LivingEntity bearer) {
		int touched = 0;
		Box area = Box.of(bearer.getPos(), AURA_RADIUS * 2, AURA_RADIUS, AURA_RADIUS * 2);
		for (HostileEntity e : world.getEntitiesByClass(HostileEntity.class, area, h -> h.isAlive() && h.getPos().distanceTo(bearer.getPos()) <= AURA_RADIUS)) {
			e.damage(world.getDamageSources().indirectMagic(bearer, bearer), e.isUndead() ? AURA_DAMAGE * 2 : AURA_DAMAGE);
			e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, AURA_PERIOD_TICKS + 20, 0, true, false, false));
			touched++;
		}
		for (int i = 0; i < 8; i++) {
			double a = (world.getTime() / 8.0 + i) * Math.PI / 4;
			world.spawnParticles(ParticleTypes.END_ROD, bearer.getX() + Math.cos(a) * AURA_RADIUS, bearer.getY() + 0.3, bearer.getZ() + Math.sin(a) * AURA_RADIUS, 1, 0, 0.02, 0, 0.0);
		}
		return touched;
	}

	/** The circles of light: ticked by the server, nothing is stored. */
	public static final class Sanctuaries {
		private record Circle(ServerWorld world, Vec3d center, long until) {
		}

		private final List<Circle> circles = new ArrayList<>();

		public void plant(ServerWorld world, Vec3d center, long untilTick) {
			circles.add(new Circle(world, center, untilTick));
		}

		public int active() {
			return circles.size();
		}

		public void tick(long serverTick) {
			if (circles.isEmpty()) {
				return;
			}
			circles.removeIf(c -> c.world().getTime() >= c.until());
			if (serverTick % 10 != 0) {
				return;
			}
			for (Circle c : circles) {
				Box area = Box.of(c.center(), SANCTUARY_RADIUS * 2, 4, SANCTUARY_RADIUS * 2);
				for (LivingEntity e : c.world().getEntitiesByClass(LivingEntity.class, area, l -> l.isAlive() && l.getPos().distanceTo(c.center()) <= SANCTUARY_RADIUS)) {
					if (e instanceof PlayerEntity || e instanceof LuminaryNpcEntity) {
						e.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 40, 0, true, false, true));
						e.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 0, true, false, true));
					} else if (e instanceof HostileEntity) {
						e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 0, true, false, true));
						e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 40, 0, true, false, true));
					}
				}
				for (int i = 0; i < 12; i++) {
					double a = (serverTick / 10.0 + i) * Math.PI / 6;
					c.world().spawnParticles(ParticleTypes.END_ROD, c.center().x + Math.cos(a) * SANCTUARY_RADIUS, c.center().y + 0.2, c.center().z + Math.sin(a) * SANCTUARY_RADIUS, 1, 0, 0.05, 0, 0.0);
				}
			}
		}
	}

	// ------------------------------------------------------------------ item plumbing

	@Override
	public com.google.common.collect.Multimap<net.minecraft.entity.attribute.EntityAttribute, EntityAttributeModifier> getAttributeModifiers(EquipmentSlot slot) {
		if (slot != EquipmentSlot.MAINHAND) {
			return super.getAttributeModifiers(slot);
		}
		return com.google.common.collect.ImmutableMultimap.of(
				EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(ATTACK_DAMAGE_MODIFIER_ID, "Weapon modifier", BASE_DAMAGE - 1.0, EntityAttributeModifier.Operation.ADDITION),
				EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(ATTACK_SPEED_MODIFIER_ID, "Weapon modifier", ATTACK_SPEED, EntityAttributeModifier.Operation.ADDITION));
	}

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
	public boolean canMine(net.minecraft.block.BlockState state, World world, net.minecraft.util.math.BlockPos pos, PlayerEntity miner) {
		return !miner.isCreative();
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
		if (entity instanceof PlayerEntity player) {
			LuminaryRuntime.get().ifPresent(rt -> rt.npc().behaviour().reconcileHeldBlade(stack, player));
			if (selected && world.getTime() % AURA_PERIOD_TICKS == 0 && world instanceof ServerWorld sw) {
				aura(sw, player);
			}
		}
		// evidence fades a mark at a time when the sword rests
		int marks = evidence(stack);
		NbtCompound nbt = stack.getNbt();
		if (marks > 0 && nbt != null && world.getTime() - nbt.getLong(NBT_LAST_HIT) >= fadeTicks(stack)) {
			setEvidence(stack, marks - 1, world.getTime());
		}
	}

	/** The name shimmers: each character cycles through gold, white and sky blue, offset along the word, re-rendered every frame. */
	private static final int[] SHIMMER = {0xFFE27A, 0xFFF6D5, 0xFFFFFF, 0xBFE9FF, 0x8FD3FF, 0xBFE9FF, 0xFFFFFF, 0xFFF6D5};

	@Override
	public Text getName(ItemStack stack) {
		return shimmer(net.minecraft.util.Language.getInstance().get(getTranslationKey(stack)));
	}

	/** Per-character gold–white–sky colouring that moves with time; shared with the vestments. */
	public static Text shimmer(String name) {
		double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * SHIMMER.length;
		net.minecraft.text.MutableText out = Text.empty();
		int i = 0;
		for (int cp : name.codePoints().toArray()) {
			double t = phase + i * 0.6;
			int a = ((int) Math.floor(t)) % SHIMMER.length;
			int b = (a + 1) % SHIMMER.length;
			double f = t - Math.floor(t);
			int color = lerp(SHIMMER[a], SHIMMER[b], f);
			out.append(Text.literal(new String(Character.toChars(cp))).styled(st -> st.withColor(color).withBold(true)));
			i++;
		}
		return out;
	}

	private static int lerp(int c1, int c2, double f) {
		int r = (int) Math.round(((c1 >> 16) & 255) * (1 - f) + ((c2 >> 16) & 255) * f);
		int g = (int) Math.round(((c1 >> 8) & 255) * (1 - f) + ((c2 >> 8) & 255) * f);
		int b = (int) Math.round((c1 & 255) * (1 - f) + (c2 & 255) * f);
		return (r << 16) | (g << 8) | b;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		String k = "item.luminary.holy_sword_of_lumin.";
		tooltip.add(Text.translatable("item.luminary.rarity.godlike").styled(st -> st.withColor(GODLIKE_COLOR).withBold(true)));
		ownerLine(stack).ifPresent(tooltip::add);
		tooltip.add(Text.translatable(k + "lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.translatable(k + "lore2").formatted(Formatting.GRAY, Formatting.ITALIC));
		tooltip.add(Text.empty());
		// unique parameters, in the pack's blue "+x" style
		tooltip.add(Text.translatable(k + "param.revelation", String.format(java.util.Locale.ROOT, "%.2f", REVELATION_CHANCE)).formatted(Formatting.BLUE));
		tooltip.add(Text.translatable(k + "param.marked", (int) Math.round(MARKED_BONUS * 100)).formatted(Formatting.BLUE));
		int marks = evidence(stack);
		tooltip.add(Text.translatable(k + "witness", marks, MAX_EVIDENCE).formatted(Formatting.AQUA));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + "effect.title").formatted(Formatting.GOLD));
		tooltip.add(Text.translatable(k + "effect.text", (int) AURA_RADIUS, AURA_PERIOD_TICKS / 20).formatted(Formatting.GRAY));
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(k + "use.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable(k + "verdict", (int) (VERDICT_BASE + VERDICT_PER_EVIDENCE * marks)).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + VERDICT_COOLDOWN / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.translatable(k + "sneak.title").formatted(Formatting.GREEN));
		tooltip.add(Text.translatable(k + "sanctuary", SANCTUARY_COST).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("⌛ " + SANCTUARY_COOLDOWN / 20 + "s").formatted(Formatting.GOLD, Formatting.ITALIC));
		tooltip.add(Text.empty());
		NbtCompound nbt = stack.getNbt();
		String rune = nbt == null ? "" : nbt.getString(NBT_RUNE);
		String nether = nbt == null ? "" : nbt.getString(NBT_NETHER_RUNE);
		tooltip.add((rune.isEmpty() ? Text.translatable(k + "socket.rune.empty") : Text.translatable(k + "rune." + rune)).formatted(Formatting.LIGHT_PURPLE));
		if (!rune.isEmpty()) {
			tooltip.add(Text.translatable(k + "rune." + rune + ".text").formatted(Formatting.DARK_GRAY));
		}
		tooltip.add((nether.isEmpty() ? Text.translatable(k + "socket.nether.empty") : Text.translatable(k + "rune." + nether)).formatted(Formatting.RED));
		if (!nether.isEmpty()) {
			tooltip.add(Text.translatable(k + "rune." + nether + ".text").formatted(Formatting.DARK_GRAY));
		}
		tooltip.add(Text.empty());
		tooltip.add(Text.translatable(isBound(stack) ? k + "bound" : k + "unbound").formatted(Formatting.DARK_PURPLE));
	}
}
