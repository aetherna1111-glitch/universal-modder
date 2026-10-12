package dev.luminary.core.events;

import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.ids.LuminaryId;

import java.time.Instant;
import java.util.UUID;

/**
 * Events published by the domain core. Subsystems communicate through these instead of direct calls, so the
 * Catalog never knows about Progression and the NPC only observes.
 */
public sealed interface DomainEvent {
	Instant at();

	/** A discovery template was observed for the first time; {@code firstObserver} is permanent attribution. */
	record DiscoveryObserved(Instant at, LuminaryId discovery, UUID firstObserver) implements DomainEvent {}

	/** Additional evidence was attached to a discovery (any status). */
	record EvidenceAdded(Instant at, LuminaryId discovery, LuminaryId evidenceId, UUID contributor) implements DomainEvent {}

	/** The status of a discovery changed (OBSERVED→VERIFIED or VERIFIED→APPLIED). */
	record DiscoveryStatusChanged(Instant at, LuminaryId discovery, DiscoveryStatus from, DiscoveryStatus to, UUID actor) implements DomainEvent {}
}
