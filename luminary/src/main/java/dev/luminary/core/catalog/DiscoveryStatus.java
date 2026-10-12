package dev.luminary.core.catalog;

/**
 * The three states the owner's design keeps distinct. Knowledge (VERIFIED) never implies capability (APPLIED):
 * only Progression may move a record to APPLIED.
 */
public enum DiscoveryStatus {
	/** A player encountered the thing for the first time. */
	OBSERVED,
	/** Enough evidence exists; the knowledge is public (subject to visibility). */
	VERIFIED,
	/** The civilization can use the knowledge through an unlocked technology. */
	APPLIED;

	public boolean isAtLeast(DiscoveryStatus other) {
		return ordinal() >= other.ordinal();
	}
}
