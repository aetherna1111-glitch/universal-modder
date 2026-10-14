package dev.luminary.fabric.npc;

import dev.luminary.core.npc.presence.Activity;
import dev.luminary.core.npc.routine.RoutineSlot;
import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Daily life: walk (really walk) to the place the current routine slot names and do the slot's activity there with
 * simple props: a book in hand while reading, a quill-book and an occasional arm swing while writing, crouching and
 * looking at nearby blocks while investigating, standing still while resting. Lower priority than attending a player,
 * tasks, follow orders and self-defence, so any of those interrupts it; when they end the slot is recomputed from the
 * clock and the routine simply resumes. Nothing here teleports or edits the world.
 */
final class RoutineGoal extends Goal {
	/** The slot in force and the place to be. */
	record Target(RoutineSlot slot, BlockPos place) {
	}

	private static final double ARRIVE_DISTANCE = 2.0;
	private static final int REPATH_TICKS = 40;

	private final LuminaryNpcEntity npc;
	private Target target;
	private boolean arrived;
	private int ticks;
	private ItemStack prop = ItemStack.EMPTY;

	RoutineGoal(LuminaryNpcEntity npc) {
		this.npc = npc;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	private Optional<Target> current() {
		return LuminaryRuntime.get().flatMap(rt -> rt.npc().behaviour().routineTarget(npc));
	}

	@Override
	public boolean canStart() {
		if (npc.isDown() || npc.hasActiveTask() || npc.followTarget() != null) {
			return false;
		}
		Optional<Target> t = current();
		if (t.isEmpty()) {
			return false;
		}
		target = t.get();
		return true;
	}

	@Override
	public boolean shouldContinue() {
		if (npc.isDown() || npc.hasActiveTask() || npc.followTarget() != null) {
			return false;
		}
		Optional<Target> t = current();
		return t.isPresent() && t.get().equals(target);
	}

	@Override
	public void start() {
		arrived = false;
		ticks = 0;
		npc.getNavigation().stop();
	}

	@Override
	public void stop() {
		clearProp();
		npc.setRoutineActivity(null);
		npc.setSneaking(false);
		npc.getNavigation().stop();
		target = null;
	}

	@Override
	public void tick() {
		if (target == null) {
			return;
		}
		ticks++;
		Vec3d place = Vec3d.ofBottomCenter(target.place());
		double distance = npc.getPos().distanceTo(place);
		if (distance > ARRIVE_DISTANCE) {
			if (arrived) {
				arrived = false;
				clearProp();
				npc.setRoutineActivity(Activity.WALKING);
			}
			if (ticks % REPATH_TICKS == 1 || npc.getNavigation().isIdle()) {
				npc.getNavigation().startMovingTo(place.x, place.y, place.z, 0.7);
			}
			npc.setRoutineActivity(Activity.WALKING);
			return;
		}
		if (!arrived) {
			arrived = true;
			npc.getNavigation().stop();
			LuminaryRuntime.get().ifPresent(rt -> rt.npc().behaviour().onRoutineArrived(npc, target.slot()));
		}
		Activity a = target.slot().activity();
		npc.setRoutineActivity(a);
		switch (a) {
			case READING -> {
				holdProp(Items.BOOK);
				npc.getLookControl().lookAt(place.x, place.y + 0.6, place.z);
			}
			case WRITING -> {
				holdProp(Items.WRITABLE_BOOK);
				npc.getLookControl().lookAt(place.x, place.y + 0.8, place.z);
				if (ticks % 50 == 0) {
					npc.swingHand(Hand.MAIN_HAND);
				}
			}
			case INVESTIGATING -> {
				clearProp();
				npc.setSneaking((ticks / 60) % 2 == 0);
				int i = (ticks / 40) % 4;
				double dx = i == 0 ? 1.5 : i == 2 ? -1.5 : 0;
				double dz = i == 1 ? 1.5 : i == 3 ? -1.5 : 0;
				npc.getLookControl().lookAt(place.x + dx, place.y + 0.2, place.z + dz);
			}
			default -> {
				clearProp();
				npc.setSneaking(false);
				npc.getLookControl().lookAt(place.x, place.y + 1.0, place.z);
			}
		}
	}

	private void holdProp(net.minecraft.item.Item item) {
		if (!npc.getMainHandStack().isEmpty() && !npc.getMainHandStack().isOf(item)) {
			return;   // never displace a tool or the blade
		}
		if (!npc.getMainHandStack().isOf(item)) {
			prop = new ItemStack(item);
			npc.equipStack(EquipmentSlot.MAINHAND, prop);
		}
	}

	private void clearProp() {
		if (!prop.isEmpty() && npc.getMainHandStack() == prop) {
			npc.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		prop = ItemStack.EMPTY;
	}
}
