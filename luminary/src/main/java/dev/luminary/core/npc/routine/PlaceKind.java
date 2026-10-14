package dev.luminary.core.npc.routine;

/** The named places an NPC's day revolves around; set by operators (or later by the player handing a place over). */
public enum PlaceKind {
	/** Where it reads: a lectern, bookshelf or just a quiet corner. */
	STUDY,
	/** Where it writes its notes. */
	DESK,
	/** Where it investigates: a dig, a strange block, the edge of the settlement. */
	SITE,
	/** Where it rests at night. */
	HOME,
	/** Where it works with its hands. */
	WORKSHOP
}
