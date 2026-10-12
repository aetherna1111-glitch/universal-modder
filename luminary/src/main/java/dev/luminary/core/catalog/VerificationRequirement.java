package dev.luminary.core.catalog;

import dev.luminary.core.ids.LuminaryId;

import java.util.Set;

/**
 * What a discovery needs before it may become VERIFIED. All requirements are about evidence and other knowledge,
 * never about elapsed time.
 */
public record VerificationRequirement(int minDistinctContributors, int minSamples, Set<LuminaryId> requiredExperiments,
									  Set<LuminaryId> requiredVerifiedDiscoveries) {
	public static final VerificationRequirement NONE = new VerificationRequirement(1, 0, Set.of(), Set.of());

	public VerificationRequirement {
		if (minDistinctContributors < 1) {
			throw new IllegalArgumentException("minDistinctContributors must be >= 1");
		}
		if (minSamples < 0) {
			throw new IllegalArgumentException("minSamples must be >= 0");
		}
		requiredExperiments = Set.copyOf(requiredExperiments);
		requiredVerifiedDiscoveries = Set.copyOf(requiredVerifiedDiscoveries);
	}
}
