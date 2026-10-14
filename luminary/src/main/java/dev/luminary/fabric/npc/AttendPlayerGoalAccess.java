package dev.luminary.fabric.npc;

import net.minecraft.entity.player.PlayerEntity;

/** Test and tooling access to the gaze test of {@link AttendPlayerGoal} (the goal itself is package-private). */
public final class AttendPlayerGoalAccess {
	private AttendPlayerGoalAccess() {
	}

	public static boolean isLookingAt(PlayerEntity player, LuminaryNpcEntity npc) {
		return AttendPlayerGoal.isLookingAt(player, npc);
	}
}
