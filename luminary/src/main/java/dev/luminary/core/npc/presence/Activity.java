package dev.luminary.core.npc.presence;

/** What the NPC is visibly doing right now; drives whispers and routine props, never gameplay rules. */
public enum Activity {
	IDLE, WALKING, GATHERING, CARRYING, SMELTING, FETCHING, READING, WRITING, INVESTIGATING, RESTING, GUARDED, FIGHTING, DOWN, RECOVERING;

	/** The authored line group for this activity ({@code mutter.<name>}). */
	public String mutterKey() {
		return "mutter." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
