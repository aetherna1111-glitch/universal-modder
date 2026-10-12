package dev.luminary.core.npc.memory;

/**
 * Where a memory came from. The store trusts them in this order: a world observation beats what a player said,
 * which beats what a model inferred. Authored entries are administrator-written facts.
 */
public enum Provenance {
	OBSERVED_IN_WORLD(3),
	AUTHORED(3),
	PLAYER_SAID(2),
	MODEL_INFERRED(1);

	private final int trust;

	Provenance(int trust) {
		this.trust = trust;
	}

	public int trust() {
		return trust;
	}
}
