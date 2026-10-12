package dev.luminary.core.progression;

import dev.luminary.core.ids.LuminaryId;

/**
 * A condition for entering an era or unlocking a technology. Conditions are about knowledge, infrastructure and
 * external signals; there is deliberately no "time elapsed" prerequisite.
 * <p>{@code Facility} (City) is not part of this slice; it will be added together with the City subsystem.</p>
 */
public sealed interface Prerequisite {
	/** The civilization is in {@code era} or a later one. */
	record EraAtLeast(LuminaryId era) implements Prerequisite {}

	/** The discovery is VERIFIED (or APPLIED) in the Catalog. */
	record DiscoveryVerified(LuminaryId discovery) implements Prerequisite {}

	/** Another technology is unlocked. */
	record TechnologyUnlocked(LuminaryId technology) implements Prerequisite {}

	/** A named numeric signal (set by integrations or admins) is at least {@code minValue}. */
	record Signal(LuminaryId signal, long minValue) implements Prerequisite {}
}
