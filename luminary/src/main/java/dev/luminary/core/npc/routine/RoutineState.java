package dev.luminary.core.npc.routine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Per-NPC routine data: the switch and the known places (packed block positions). Persisted. */
public final class RoutineState {
	private boolean enabled = true;
	private final Map<PlaceKind, Long> places = new EnumMap<>(PlaceKind.class);

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Optional<Long> place(PlaceKind kind) {
		return Optional.ofNullable(places.get(kind));
	}

	public void setPlace(PlaceKind kind, long packedPos) {
		places.put(kind, packedPos);
	}

	public boolean clearPlace(PlaceKind kind) {
		return places.remove(kind) != null;
	}

	public Map<PlaceKind, Long> places() {
		return Collections.unmodifiableMap(places);
	}
}
