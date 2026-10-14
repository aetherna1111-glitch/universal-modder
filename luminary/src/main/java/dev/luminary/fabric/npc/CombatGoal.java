package dev.luminary.fabric.npc;

import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Self-defence on the body: face the opponent while guarded; in COMBAT step towards them only inside the
 * hold-ground radius around where the fight started and strike when within reach, with a swing cooldown. Never
 * pursues beyond that radius, never chooses a target itself: the {@code CombatMachine} decides, this goal performs.
 */
final class CombatGoal extends Goal {
	private static final int SWING_COOLDOWN_TICKS = 20;

	private final LuminaryNpcEntity npc;
	private LivingEntity target;
	private int cooldown;

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
		if (distance > reach) {
			boolean mayStep = target.getPos().distanceTo(anchor) <= radius + reach;
			if (mayStep) {
				npc.getNavigation().startMovingTo(target, 1.1);
			} else if (!npc.getNavigation().isIdle()) {
				npc.getNavigation().stop();   // the opponent ran off: hold ground, no pursuit
			}
		} else if (!npc.getNavigation().isIdle()) {
			npc.getNavigation().stop();
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (b.shouldStrike(npc, target) && npc.canSee(target)) {
			b.strike(npc, target);
			cooldown = SWING_COOLDOWN_TICKS;
		}
	}
}
