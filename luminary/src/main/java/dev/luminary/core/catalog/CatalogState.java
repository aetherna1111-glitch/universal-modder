package dev.luminary.core.catalog;

import dev.luminary.core.ids.LuminaryId;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The persisted part of the Catalog: records by id. Templates are definitions (data pack) and are held separately
 * so a reload can change requirements without touching saved records.
 */
public final class CatalogState {
	private final Map<LuminaryId, DiscoveryRecord> records = new TreeMap<>();

	public Optional<DiscoveryRecord> get(LuminaryId id) {
		return Optional.ofNullable(records.get(id));
	}

	public Collection<DiscoveryRecord> all() {
		return Collections.unmodifiableCollection(records.values());
	}

	public int size() {
		return records.size();
	}

	void put(DiscoveryRecord record) {
		records.put(record.id(), record);
	}
}
