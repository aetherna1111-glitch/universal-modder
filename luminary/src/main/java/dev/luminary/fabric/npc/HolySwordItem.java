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
	public static final double BASE_DAMAGE = 9.0;      // shown as 9 attack damage with an empty ledger
	public static final double DAMAGE_PER_EVIDENCE = 0.5;
	public static final double ATTACK_SPEED = -2.4;    // 1.6 swings per second
	public static final int EVIDENCE_FADE_TICKS = 600;
	public static final double VERDICT_RANGE = 12.0;
	public static final float VERDICT_BASE = 3.0f;
	public static final float VERDICT_PER_EVIDENCE = 2.0f;
	public static final int VERDICT_COOLDOWN = 120;
	public static final int SANCTUARY_COST = 3;
	public static final int SANCTUARY_TICKS = 160;
	public static final double SANCTUARY_RADIUS = 5.0;
	public static final int SANCTUARY_COOLDOWN = 600;

	public HolySwordItem(Settings settings) {
		super(settings);
	}

	// ------------------------------------------------------------------ identity

	/** A stack bound to {@code npc}'s current custody instance, with its base attributes written. */
	public static ItemStack bound(Item item, LuminaryId npc, UUID instance) {
		ItemStack stack = new ItemStack(item);
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putString(NBT_OWNER, npc.toString());
		nbt.putUuid(NBT_INSTANCE, instance);
		writeAttributes(stack, 0);
		return stack;
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

	static void setEvidence(ItemStack stack, int marks, long now) {
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
	}

	@Override
	public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		if (!attacker.getWorld().isClient) {
			setEvidence(stack, evidence(stack) + 1, attacker.getWorld().getTime());
			if (attacker.getWorld() instanceof ServerWorld sw) {
				sw.spawnParticles(ParticleTypes.END_ROD, target.getX(), target.getBodyY(0.6), target.getZ(), 4, 0.3, 0.3, 0.3, 0.02);
			}
		}
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
		if (world.isClient || world.getTime() % 20 != 0) {
			return;
		}
		if (entity instanceof PlayerEntity player) {
			LuminaryRuntime.get().ifPresent(rt -> rt.npc().behaviour().reconcileHeldBlade(stack, player));
		}
		// evidence fades a mark at a time when the sword rests
		int marks = evidence(stack);
		NbtCompound nbt = stack.getNbt();
		if (marks > 0 && nbt != null && world.getTime() - nbt.getLong(NBT_LAST_HIT) >= EVIDENCE_FADE_TICKS) {
			setEvidence(stack, marks - 1, world.getTime());
		}
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("item.luminary.holy_sword_of_lumin.lore").formatted(Formatting.GRAY, Formatting.ITALIC));
		int marks = evidence(stack);
		tooltip.add(Text.translatable("item.luminary.holy_sword_of_lumin.witness", marks, MAX_EVIDENCE).formatted(Formatting.AQUA));
		tooltip.add(Text.translatable("item.luminary.holy_sword_of_lumin.verdict", (int) (VERDICT_BASE + VERDICT_PER_EVIDENCE * marks)).formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("item.luminary.holy_sword_of_lumin.sanctuary", SANCTUARY_COST).formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("item.luminary.holy_sword_of_lumin.bound").formatted(Formatting.DARK_PURPLE));
	}
}
