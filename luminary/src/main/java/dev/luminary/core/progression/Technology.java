package dev.luminary.core.progression;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Set;

/**
 * A civilization-level unlock. When unlocked, its {@code appliesDiscoveries} move to APPLIED in the Catalog and its
 * {@code grantsCapabilities} become available to gameplay gates (recipes, item use) in the Fabric layer.
 */
public record Technology(LuminaryId id, LuminaryId era, String titleKey, Set<Prerequisite> prerequisites,
						 Set<LuminaryId> appliesDiscoveries, Set<LuminaryId> grantsCapabilities) {
	public Technology {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(era, "era");
		Objects.requireNonNull(titleKey, "titleKey");
		prerequisites = Set.copyOf(prerequisites);
		appliesDiscoveries = Set.copyOf(appliesDiscoveries);
		grantsCapabilities = Set.copyOf(grantsCapabilities);
		for (Prerequisite p : prerequisites) {
			if (p instanceof Prerequisite.TechnologyUnlocked t && t.technology().equals(id)) {
				throw new IllegalArgumentException(id + " requires itself");
			}
		}
	}
}
