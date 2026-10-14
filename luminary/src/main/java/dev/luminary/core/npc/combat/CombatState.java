package dev.luminary.core.npc.combat;

/** The self-defence state machine's states; see {@link CombatMachine}. */
public enum CombatState {
	/** Nothing happening. */
	CALM,
	/** Was hit: shield up, warning spoken, no strike yet. */
	DEFENSIVE_WARNING,
	/** The attacker backed off or apologized: shield stays up for a while, no strike. */
	GUARDED_DEESCALATION,
	/** Repeated attacks: strikes back at the attacker within reach, holds ground, never pursues. */
	COMBAT,
	/** After a fight or a knockout: shield down, slow to re-engage, back to CALM after the recovery time. */
	RECOVERY
}
