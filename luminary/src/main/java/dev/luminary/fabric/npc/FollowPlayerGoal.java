package dev.luminary.fabric.npc;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;

/**
 * Walks towards the entity's follow target (a player chosen by an administrator or a conversation) while it is further
 * than {@code startDistance}, stopping within {@code stopDistance}. Pathfinding is budgeted to one request per second.
 */
final class FollowPlayerGoal extends Goal {
	private static final double START_DISTANCE = 4.0;
	private static final double STOP_DISTANCE = 2.5;
	private static final int REPATH_TICKS = 20;

	private final LuminaryNpcEntity npc;
	private PlayerEntity target;
	private int cooldown;

	FollowPlayerGoal(LuminaryNpcEntity npc) {
		this.npc = npc;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		target = npc.followTarget();
		return target != null && npc.squaredDistanceTo(target) > START_DISTANCE * START_DISTANCE;
	}

	@Override
	public boolean shouldContinue() {
		return target != null && target.isAlive() && npc.followTarget() == target && !npc.getNavigation().isIdle();
	}

	@Override
	public void start() {
		cooldown = 0;
	}

	@Override
	public void stop() {
		npc.getNavigation().stop();
		target = null;
	}

	@Override
	public void tick() {
		npc.getLookControl().lookAt(target, 30.0f, 30.0f);
		if (--cooldown > 0) {
			return;
		}
		cooldown = REPATH_TICKS;
		if (npc.squaredDistanceTo(target) > STOP_DISTANCE * STOP_DISTANCE) {
			npc.getNavigation().startMovingTo(target, 1.0);
		} else {
			npc.getNavigation().stop();
		}
	}
}
