package dev.luminary.fabric.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
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

	private LuminaryId npcId;
	private UUID followTarget;

	public LuminaryNpcEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		setCanPickUpLoot(false);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		goalSelector.add(2, new FollowPlayerGoal(this));
		goalSelector.add(5, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		goalSelector.add(6, new WanderAroundGoal(this, 0.6));
		goalSelector.add(7, new LookAroundGoal(this));
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
			LuminaryRuntime.get().ifPresent(rt -> rt.npc().talk(this, player));
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
	}
}
