package dev.luminary.core.npc.memory;

import java.time.Duration;

/**
 * What a memory is about, with the half-life its recency score decays by. Stable facts barely fade; small talk fades
 * in days. Narrative secrets are not memories: they live in the story definitions and are gated by the story.
 */
public enum MemoryKind {
	STABLE_FACT(Duration.ofDays(3650), 0.8),
	RECENT_INTERACTION(Duration.ofDays(2), 0.3),
	SIGNIFICANT_EVENT(Duration.ofDays(90), 0.7),
	PROMISE(Duration.ofDays(365), 0.9),
	RELATIONSHIP(Duration.ofDays(60), 0.6),
	OBSERVATION(Duration.ofDays(14), 0.5),
	DISCOVERY_CLUE(Duration.ofDays(365), 0.8),
	UNVERIFIED_ASSUMPTION(Duration.ofDays(7), 0.2);

	private final Duration halfLife;
	private final double defaultImportance;

	MemoryKind(Duration halfLife, double defaultImportance) {
		this.halfLife = halfLife;
		this.defaultImportance = defaultImportance;
	}

	public Duration halfLife() {
		return halfLife;
	}

	public double defaultImportance() {
		return defaultImportance;
	}
}
