package dev.luminary.core.npc.task;

/** Limits applied by the validators. Defaults are conservative; a config knob can override later. */
public record TaskRules(double maxRangeBlocks, int maxLogsPerTask, int breakTicksPerLog, int collectTimeoutTicks, int moveTimeoutTicks,
						int maxWithdrawCount) {
	public static final TaskRules DEFAULTS = new TaskRules(32.0, 16, 20, 400, 600, 64);

	/** Pre-slice-9 shape, kept for callers that only tune the gather limits. */
	public TaskRules(double maxRangeBlocks, int maxLogsPerTask, int breakTicksPerLog, int collectTimeoutTicks, int moveTimeoutTicks) {
		this(maxRangeBlocks, maxLogsPerTask, breakTicksPerLog, collectTimeoutTicks, moveTimeoutTicks, 64);
	}
}
