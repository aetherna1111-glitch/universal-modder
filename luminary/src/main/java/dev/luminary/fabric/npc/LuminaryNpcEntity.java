package dev.luminary.fabric.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.presence.Activity;
import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * The server-authoritative body of a Luminary NPC. Identity ({@link #npcId()}) and the follow target are the only
 * per-entity data; memory and roster live in {@code NpcWorldState}. Right-click talks (see {@link NpcService#talk}).
 * Never despawns naturally and never spawns naturally; only {@link NpcService#spawn} creates one.
 */
public class LuminaryNpcEntity extends PathAwareEntity {
	static final String NBT_NPC = "LuminaryNpc";
	static final String NBT_FOLLOW = "LuminaryFollow";

	static final String NBT_HELD = "LuminaryHeld";
	static final String NBT_DOWN = "LuminaryDown";
	static final String NBT_HELMET_OFF = "LuminaryHelmetOff";
	/** Synced so the client renders the lying pose while the NPC is knocked out. */
	private static final TrackedData<Boolean> DOWN = DataTracker.registerData(LuminaryNpcEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	private LuminaryId npcId;
	private UUID followTarget;
	/** Runtime only: what the body is visibly doing (whispers) and what the routine goal set. */
	private Activity activity = Activity.IDLE;
	private Activity routineActivity;
	/** Where the current fight started; the NPC holds ground around it. */
	private Vec3d combatAnchor;
	/** The helmet was asked off ("take off your helmet"): the vestments keep the head bare until asked back on. */
	private boolean helmetOff;
	/** Ticks left in the take-off / put-on animation (positive: taking off, negative: putting on). */
	private int helmetAnimation;
	/** Items the NPC physically carries (collected drops not yet deposited). Saved with the entity. */
	private final SimpleInventory heldItems = new SimpleInventory(9);

	public LuminaryNpcEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		setCanPickUpLoot(false);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		dataTracker.startTracking(DOWN, false);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		goalSelector.add(1, new CombatGoal(this));
		goalSelector.add(2, new TaskGoal(this));
		goalSelector.add(3, new FollowPlayerGoal(this));
		goalSelector.add(4, new AttendPlayerGoal(this));
		goalSelector.add(5, new RoutineGoal(this));
		goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		goalSelector.add(7, new WanderAroundGoal(this, 0.6, 240));   // a short stroll every ~12 s on average, not constant pacing
		goalSelector.add(8, new LookAroundGoal(this));
	}

	// ------------------------------------------------------------------ presence, combat, knockout

	public Activity activity() {
		return activity;
	}

	void setActivity(Activity activity) {
		this.activity = activity;
	}

	/** The activity the routine goal is performing, or null when the routine is not running. */
	Activity routineActivity() {
		return routineActivity;
	}

	void setRoutineActivity(Activity activity) {
		this.routineActivity = activity;
	}

	/** Test/status view of {@link #routineActivity()}. */
	public Activity routineActivityPublic() {
		return routineActivity;
	}

	public boolean helmetOff() {
		return helmetOff;
	}

	void setHelmetOff(boolean off) {
		this.helmetOff = off;
	}

	int helmetAnimation() {
		return helmetAnimation;
	}

	void setHelmetAnimation(int ticks) {
		this.helmetAnimation = ticks;
	}

	public Optional<Vec3d> combatAnchor() {
		return Optional.ofNullable(combatAnchor);
	}

	void setCombatAnchor(Vec3d anchor) {
		this.combatAnchor = anchor;
	}

	/** Knocked out: lying, AI off, invulnerable, still the same entity. */
	public boolean isDown() {
		return dataTracker.get(DOWN);
	}

	public void setDown(boolean down) {
		if (isDown() == down && (down == (getPose() == EntityPose.SLEEPING))) {
			return;
		}
		dataTracker.set(DOWN, down);
		setAiDisabled(down);
		if (down) {
			getNavigation().stop();
			clearActiveItem();
			setSneaking(false);
			setHealth(1.0f);
			setPose(EntityPose.SLEEPING);
		} else {
			setPose(EntityPose.STANDING);
		}
	}

	/** Lying down the box is a low, wide slab a player can actually aim at (the sleeping pose would shrink it to 0.2 blocks). */
	@Override
	public net.minecraft.entity.EntityDimensions getDimensions(EntityPose pose) {
		return pose == EntityPose.SLEEPING ? net.minecraft.entity.EntityDimensions.fixed(1.4f, 0.6f) : super.getDimensions(pose);
	}

	@Override
	protected float getActiveEyeHeight(EntityPose pose, net.minecraft.entity.EntityDimensions dimensions) {
		return pose == EntityPose.SLEEPING ? 0.3f : super.getActiveEyeHeight(pose, dimensions);
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		return isDown() && !source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD) || super.isInvulnerableTo(source);
	}

	/** Damage never kills: at zero the body goes down for a fixed real time instead. Attacks feed the self-defence machine. */
	@Override
	public boolean damage(DamageSource source, float amount) {
		if (getWorld().isClient) {
			return super.damage(source, amount);
		}
		if (isDown()) {
			return false;
		}
		Optional<LuminaryRuntime> rt = LuminaryRuntime.get();
		if (amount >= getHealth() && npcId != null && rt.isPresent()) {
			rt.get().npc().behaviour().knockOut(this, source);
			return true;
		}
		// a blow the raised shield stops never reaches super.damage's "hurt": vanilla returns false for a blocked hit,
		// but the shield still took it, so the behaviour must see every blow that landed on the body or on the shield
		boolean blocked = amount > 0 && isBlocking() && blockedByShield(source);
		boolean hurt = super.damage(source, amount);
		if ((hurt || blocked) && npcId != null && rt.isPresent()) {
			rt.get().npc().behaviour().onAttacked(this, source, amount);
		}
		return hurt;   // vanilla's answer: a blocked blow did not hurt the body (no knockback, no enchantment effects from the attacker's weapon)
	}

	@Override
	public void tick() {
		super.tick();
		if (!getWorld().isClient && npcId != null) {
			LuminaryRuntime.get().ifPresent(rt -> {
				rt.npc().behaviour().tickKnockout(this);
				rt.npc().behaviour().tickWeapon(this);
				rt.npc().behaviour().tickVestments(this);
			});
		}
	}

	public Optional<LuminaryId> npcId() {
		return Optional.ofNullable(npcId);
	}

	public void setNpcId(LuminaryId id) {
		this.npcId = id;
	}

	public void setFollowTarget(UUID player) {
		this.followTarget = player;
	}

	public Optional<UUID> followTargetId() {
		return Optional.ofNullable(followTarget);
	}

	public SimpleInventory heldItems() {
		return heldItems;
	}

	/** Total items the NPC carries, for accounting. */
	public int heldCount() {
		int n = 0;
		for (int i = 0; i < heldItems.size(); i++) {
			n += heldItems.getStack(i).getCount();
		}
		return n;
	}

	public boolean hasActiveTask() {
		return npcId != null && LuminaryRuntime.get().map(rt -> rt.npc().roster().get(npcId).map(r -> r.hasActiveTask()).orElse(false)).orElse(false);
	}

	@Override
	protected void mobTick() {
		super.mobTick();
		if (!getWorld().isClient && npcId != null) {
			LuminaryRuntime.get().ifPresent(rt -> {
				rt.npc().tickTask(this);
				rt.npc().tickAutonomy(this);
				rt.npc().behaviour().tickCombat(this);
				rt.npc().behaviour().tickPresence(this);
				rt.npc().behaviour().tickPlan(this);
			});
		}
	}

	/** The follow target if it is present in this world; null otherwise (the goal then idles). */
	PlayerEntity followTarget() {
		if (followTarget == null || !(getWorld() instanceof ServerWorld world)) {
			return null;
		}
		PlayerEntity byList = world.getPlayerByUuid(followTarget);
		if (byList != null) {
			return byList;
		}
		return world.getEntity(followTarget) instanceof PlayerEntity p ? p : null;
	}

	@Override
	protected ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		if (!getWorld().isClient) {
			ItemStack inHand = player.getStackInHand(hand);
			if (isDown()) {
				LuminaryRuntime.get().ifPresent(rt -> {
					var result = rt.npc().behaviour().interactDown(this, player, inHand);
					if (result == BehaviourService.DownInteraction.NOTHING) {
						String key = player.isSneaking() && inHand.isEmpty() ? "npc.luminary.down.no_weapon" : inHand.isEmpty() ? "npc.luminary.down.bag_empty" : "npc.luminary.down.hint";
						player.sendMessage(net.minecraft.text.Text.translatable(key).formatted(net.minecraft.util.Formatting.GRAY, net.minecraft.util.Formatting.ITALIC), true);
					}
				});
			} else if (inHand.isOf(LuminaryItems.LUMIN_BLADE)) {
				LuminaryRuntime.get().ifPresent(rt -> {
					if (!rt.npc().behaviour().offerBladeBack(this, player, inHand)) {
						rt.npc().talk(this, player);
					}
				});
			} else if (GearManager.isTool(inHand) || GearManager.isArmor(inHand)) {
				LuminaryRuntime.get().ifPresent(rt -> rt.npc().offer(this, player, inHand));
			} else if (player.isSneaking()) {
				LuminaryRuntime.get().ifPresent(rt -> rt.npc().talk(this, player));
			} else if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp && npcId != null
					&& LuminaryRuntime.get().map(rt -> rt.npc().trade().trades(npcId)).orElse(false)) {
				// a plain right-click opens his dialogue screen: quests, the shop, a look at his gear; conversation is by chat (or sneak + right-click)
				LuminaryRuntime.get().ifPresent(rt -> rt.npc().trade().open(this, sp, ""));
			} else {
				player.openHandledScreen(GearViewScreenHandler.factory(this));
			}
		}
		return ActionResult.success(getWorld().isClient);
	}

	@Override
	public boolean cannotDespawn() {
		return true;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!getWorld().isClient && reason.shouldDestroy()) {
			LuminaryRuntime.get().ifPresent(rt -> rt.npc().onBodyDestroyed(this));
		}
		super.remove(reason);
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		if (npcId != null) {
			nbt.putString(NBT_NPC, npcId.toString());
		}
		if (followTarget != null) {
			nbt.putUuid(NBT_FOLLOW, followTarget);
		}
		nbt.put(NBT_HELD, heldItems.toNbtList());
		nbt.putBoolean(NBT_DOWN, isDown());
		nbt.putBoolean(NBT_HELMET_OFF, helmetOff);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains(NBT_NPC)) {
			try {
				npcId = LuminaryId.parse(nbt.getString(NBT_NPC));
			} catch (IllegalArgumentException e) {
				npcId = null;   // invalid id in old data: the body exists but is not a registered NPC
			}
		}
		followTarget = nbt.containsUuid(NBT_FOLLOW) ? nbt.getUuid(NBT_FOLLOW) : null;
		heldItems.clear();
		if (nbt.contains(NBT_HELD, NbtElement.LIST_TYPE)) {
			heldItems.readNbtList(nbt.getList(NBT_HELD, NbtElement.COMPOUND_TYPE));
		}
		helmetOff = nbt.getBoolean(NBT_HELMET_OFF);
		if (nbt.getBoolean(NBT_DOWN)) {
			dataTracker.set(DOWN, true);
			setPose(EntityPose.SLEEPING);
			setAiDisabled(true);
		}
	}
}
