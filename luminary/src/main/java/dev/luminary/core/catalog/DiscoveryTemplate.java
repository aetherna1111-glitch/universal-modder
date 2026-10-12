package dev.luminary.core.catalog;

import dev.luminary.core.ids.LuminaryId;

import java.util.Objects;
import java.util.Set;

/** The definition of a discoverable thing. Loaded from data packs; records reference templates by id. */
public record DiscoveryTemplate(LuminaryId id, String titleKey, VerificationRequirement verification, Set<LuminaryId> dependsOn,
								Visibility visibility) {
	public DiscoveryTemplate {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(titleKey, "titleKey");
		Objects.requireNonNull(verification, "verification");
		dependsOn = Set.copyOf(dependsOn);
		Objects.requireNonNull(visibility, "visibility");
		if (dependsOn.contains(id)) {
			throw new IllegalArgumentException(id + " depends on itself");
		}
	}
}
