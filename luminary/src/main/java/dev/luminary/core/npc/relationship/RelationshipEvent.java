package dev.luminary.core.npc.relationship;

/**
 * Authoritative things a player did that the NPC's standing towards them reacts to. Captured only from server-side
 * observations (damage, inventory moves, chat the NPC heard), never from what a model inferred.
 */
public enum RelationshipEvent {
	/** Spoke with the NPC. */
	CONVERSATION,
	/** Asked for and received help (a task). */
	REQUESTED_WORK,
	/** Handed over a tool, armour or any useful thing. */
	GIFT,
	/** Hit the NPC without a declared duel. */
	ATTACKED,
	/** Hit the NPC after being warned. */
	ATTACKED_WHEN_WARNED,
	/** Hit the NPC inside a duel both agreed to. Threat rises, karma does not. */
	DUEL_HIT,
	/** Said sorry after a fight. */
	APOLOGIZED,
	/** Took the NPC's unique weapon while it was down. */
	STOLE_WEAPON,
	/** Gave the unique weapon back before it would have returned by itself. */
	RETURNED_WEAPON,
	/** Fed or otherwise helped the NPC while it was knocked out. */
	HELPED_WHILE_DOWN,
	/** Took the NPC's ordinary belongings while it was knocked out. */
	LOOTED_WHILE_DOWN
}
