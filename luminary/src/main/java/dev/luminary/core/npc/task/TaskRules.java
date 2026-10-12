package dev.luminary.core.npc.task;

/** Limits applied by the validators. Defaults are conservative; a config knob can override later. */
public record TaskRules(double maxRangeBlocks, int maxLogsPerTask, int breakTicksPerLog, int collectTimeoutTicks, int moveTimeoutTicks) {
	public static final TaskRules DEFAULTS = new TaskRules(32.0, 16, 20, 400, 600);
}
