package dev.luminary.fabric.npc;

import net.minecraft.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Holds the MOVE and LOOK controls while the NPC has an active task so follow/wander goals stay out of the way.
 * The work itself happens in {@code TaskExecutor} from the entity's server tick, not here.
 */
final class TaskGoal extends Goal {
	private final LuminaryNpcEntity npc;

	TaskGoal(LuminaryNpcEntity npc) {
		this.npc = npc;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return npc.hasActiveTask();
	}

	@Override
	public boolean shouldContinue() {
		return npc.hasActiveTask();
	}
}
