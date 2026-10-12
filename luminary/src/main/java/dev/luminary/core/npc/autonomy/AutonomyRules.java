package dev.luminary.core.npc.autonomy;

import java.time.Duration;

/**
 * Hard limits on self-directed behaviour. {@code maxDecisionsPerWindow} bounds how many tasks the NPC may start on its
 * own in {@code window}; a target that failed is avoided for {@code failureCooldown}.
 */
public record AutonomyRules(int thinkIntervalTicks, int maxDecisionsPerWindow, Duration window, Duration failureCooldown) {
	public static final AutonomyRules DEFAULTS = new AutonomyRules(100, 12, Duration.ofHours(1), Duration.ofMinutes(10));

	public AutonomyRules {
		if (thinkIntervalTicks < 1 || maxDecisionsPerWindow < 0 || window.isNegative() || failureCooldown.isNegative()) {
			throw new IllegalArgumentException("invalid autonomy rules");
		}
	}
}
