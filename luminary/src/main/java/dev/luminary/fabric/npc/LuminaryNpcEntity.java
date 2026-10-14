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

	private LuminaryId npcId;
	private UUID followTarget;
	/** Items the NPC physically carries (collected drops not yet deposited). Saved with the entity. */
	private final SimpleInventory heldItems = new SimpleInventory(9);

	public LuminaryNpcEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		setCanPickUpLoot(false);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		goalSelector.add(1, new TaskGoal(this));
		goalSelector.add(2, new FollowPlayerGoal(this));
		goalSelector.add(3, new AttendPlayerGoal(this));
		goalSelector.add(5, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		goalSelector.add(6, new WanderAroundGoal(this, 0.6, 240));   // a short stroll every ~12 s on average, not constant pacing
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
			if (GearManager.isTool(inHand) || GearManager.isArmor(inHand)) {
				LuminaryRuntime.get().ifPresent(rt -> rt.npc().offer(this, player, inHand));
			} else {
				LuminaryRuntime.get().ifPresent(rt -> rt.npc().talk(this, player));
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
	}
}
