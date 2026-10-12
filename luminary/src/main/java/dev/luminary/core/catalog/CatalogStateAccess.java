package dev.luminary.core.catalog;

/** Restores records from persistence. Only the persistence package should call this. */
public final class CatalogStateAccess {
	private CatalogStateAccess() {
	}

	public static void put(CatalogState state, DiscoveryRecord record) {
		state.put(record);
	}
}
