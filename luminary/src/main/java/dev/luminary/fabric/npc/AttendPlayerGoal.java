package dev.luminary.fabric.npc;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * Presence: when a nearby player looks at the NPC, it stops walking and faces them, as a person would. The player's
 * gaze is the cue (their look ray crosses the NPC's box, grown by {@value #SLACK}, with a clear line of sight);
 * the NPC keeps attending for a short grace period after the player looks away so it does not twitch.
 * Tasks and follow orders have priority (they hold the controls first).
 */
final class AttendPlayerGoal extends Goal {
	static final double RANGE = 8.0;
	static final double SLACK = 0.5;
	private static final int GRACE_TICKS = 40;

	private final LuminaryNpcEntity npc;
	private PlayerEntity watcher;
	private int grace;

	AttendPlayerGoal(LuminaryNpcEntity npc) {
		this.npc = npc;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	/**
	 * Whether {@code player} is looking at {@code npc}: close enough, the gaze ray passes through the NPC's bounding box
	 * (grown by {@value #SLACK} blocks so a glance at the shoulder counts) and the line of sight is clear.
	 */
	static boolean isLookingAt(PlayerEntity player, LuminaryNpcEntity npc) {
		if (player == null || player.isSpectator() || !player.isAlive() || player.squaredDistanceTo(npc) > RANGE * RANGE) {
			return false;
		}
		Vec3d eyes = player.getEyePos();
		Vec3d end = eyes.add(player.getRotationVec(1.0f).multiply(RANGE));
		return npc.getBoundingBox().expand(SLACK).raycast(eyes, end).isPresent() && player.canSee(npc);
	}

	private PlayerEntity findWatcher() {
		PlayerEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (PlayerEntity p : npc.getWorld().getPlayers()) {
			if (isLookingAt(p, npc)) {
				double d = p.squaredDistanceTo(npc);
				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
		}
		return best;
	}

	@Override
	public boolean canStart() {
		if (npc.hasActiveTask() || npc.followTarget() != null) {
			return false;
		}
		watcher = findWatcher();
		return watcher != null;
	}

	@Override
	public boolean shouldContinue() {
		if (npc.hasActiveTask() || npc.followTarget() != null || watcher == null) {
			return false;
		}
		if (isLookingAt(watcher, npc)) {
			grace = GRACE_TICKS;
			return true;
		}
		return --grace > 0;
	}

	@Override
	public void start() {
		grace = GRACE_TICKS;
		npc.getNavigation().stop();
	}

	@Override
	public void stop() {
		watcher = null;
	}

	@Override
	public void tick() {
		if (!npc.getNavigation().isIdle()) {
			npc.getNavigation().stop();
		}
		if (watcher != null) {
			npc.getLookControl().lookAt(watcher, 30.0f, 30.0f);
		}
	}
}
