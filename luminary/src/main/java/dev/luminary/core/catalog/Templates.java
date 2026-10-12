package dev.luminary.core.catalog;

import dev.luminary.core.ids.LuminaryId;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Registry of {@link DiscoveryTemplate}s, replaced wholesale on data pack reload. */
public final class Templates {
	private final Map<LuminaryId, DiscoveryTemplate> byId = new LinkedHashMap<>();

	public Templates add(DiscoveryTemplate t) {
		if (byId.putIfAbsent(t.id(), t) != null) {
			throw new IllegalArgumentException("duplicate discovery template " + t.id());
		}
		return this;
	}

	public Optional<DiscoveryTemplate> get(LuminaryId id) {
		return Optional.ofNullable(byId.get(id));
	}

	public Collection<DiscoveryTemplate> all() {
		return Collections.unmodifiableCollection(byId.values());
	}

	public int size() {
		return byId.size();
	}

	/** Fails when a template depends on an unknown template; called after a full load. */
	public void validate() {
		for (DiscoveryTemplate t : byId.values()) {
			for (LuminaryId dep : t.dependsOn()) {
				if (!byId.containsKey(dep)) {
					throw new IllegalStateException(t.id() + " depends on unknown template " + dep);
				}
			}
		}
	}
}
