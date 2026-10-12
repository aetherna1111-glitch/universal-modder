package dev.luminary.core.catalog;

import dev.luminary.core.ids.LuminaryId;

import java.util.List;
import java.util.Objects;

/** Immutable snapshot of one catalogued discovery. Mutations produce new records via {@link CatalogService}. */
public record DiscoveryRecord(LuminaryId id, DiscoveryStatus status, Attribution firstObserver, List<Evidence> evidence,
							  List<StatusChange> history) {
	public DiscoveryRecord {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(firstObserver, "firstObserver");
		evidence = List.copyOf(evidence);
		history = List.copyOf(history);
	}

	DiscoveryRecord withEvidence(Evidence e) {
		List<Evidence> list = new java.util.ArrayList<>(evidence);
		list.add(e);
		return new DiscoveryRecord(id, status, firstObserver, list, history);
	}

	DiscoveryRecord withStatus(DiscoveryStatus to, StatusChange change) {
		List<StatusChange> list = new java.util.ArrayList<>(history);
		list.add(change);
		return new DiscoveryRecord(id, to, firstObserver, evidence, list);
	}
}
