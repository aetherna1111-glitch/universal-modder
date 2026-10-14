package dev.luminary.fabric.npc;

import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Self-defence on the body: face the opponent while guarded; in COMBAT close in and strike when within reach, with a
 * swing cooldown. An opponent still striking him is pressed wherever they are; one who stopped is followed only
 * inside the hold-ground radius around where the fight started. Never chooses a target itself: the
 * {@code CombatMachine} decides, this goal performs.
 */
final class CombatGoal extends Goal {
	private static final int SWING_COOLDOWN_TICKS = 20;
	private static final int JUDGE_COOLDOWN_TICKS = 120;
	private static final double JUDGE_RANGE = 10.0;
	private static final double PURSUIT_SPEED = 1.35;
	private static final int PRESS_TICKS = 20 * 8;

	private final LuminaryNpcEntity npc;
	private LivingEntity target;
	private int cooldown;
	private int judgeCooldown;

	CombatGoal(LuminaryNpcEntity npc) {
		this.npc = npc;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	private Optional<BehaviourService> behaviour() {
		return LuminaryRuntime.get().map(rt -> rt.npc().behaviour());
	}

	@Override
	public boolean canStart() {
		if (npc.isDown()) {
			return false;
		}
		target = behaviour().flatMap(b -> b.combatTarget(npc)).orElse(null);
		return target != null;
	}

	@Override
	public boolean shouldContinue() {
		return canStart();
	}

	@Override
	public void start() {
		npc.getNavigation().stop();
		cooldown = 5;
	}

	@Override
	public void stop() {
		target = null;
		npc.getNavigation().stop();
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (target == null) {
			return;
		}
		npc.getLookControl().lookAt(target, 30.0f, 30.0f);
		BehaviourService b = behaviour().orElse(null);
		if (b == null) {
			return;
		}
		if (!b.inCombat(npc)) {
			if (!npc.getNavigation().isIdle()) {
				npc.getNavigation().stop();
			}
			return;
		}
		double distance = Math.sqrt(target.squaredDistanceTo(npc));
		double reach = b.config().combat().strikeReach();
		Vec3d anchor = npc.combatAnchor().orElse(npc.getPos());
		double radius = b.config().combat().holdGroundRadius();
		// an opponent who struck within the last PRESS_TICKS is pressed wherever they stand; one who stopped and walked off is not chased past the radius
		boolean pressing = npc.getAttacker() == target && npc.age - npc.getLastAttackedTime() < PRESS_TICKS;
		if (distance > reach * 0.8) {
			boolean mayStep = pressing || target.getPos().distanceTo(anchor) <= radius + reach;
			if (mayStep) {
				if (npc.getNavigation().isIdle() || npc.age % 10 == 0) {
					npc.getNavigation().startMovingTo(target, PURSUIT_SPEED);   // re-pathed every half second: a strafing player is followed, not lost
				}
			} else if (!npc.getNavigation().isIdle()) {
				npc.getNavigation().stop();   // the opponent ran off: hold ground, no pursuit
			}
		} else if (!npc.getNavigation().isIdle()) {
			npc.getNavigation().stop();
		}
		if (judgeCooldown > 0) {
			judgeCooldown--;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (b.shouldStrike(npc, target) && (npc.canSee(target) || distance <= 2.0)) {
			b.strike(npc, target);
			cooldown = SWING_COOLDOWN_TICKS;
		} else if (distance > reach && distance <= JUDGE_RANGE && judgeCooldown == 0 && npc.canSee(target)) {
			// beyond reach and not pursuing: the sword's beam instead of a chase
			if (b.judge(npc, target)) {
				judgeCooldown = JUDGE_COOLDOWN_TICKS;
				cooldown = SWING_COOLDOWN_TICKS;
			}
		}
	}
}
