package dev.luminary.core.progression;

import dev.luminary.core.ids.LuminaryId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Persisted progression: current era, unlocked technologies with their unlock records, and signal values. */
public final class ProgressionState {
	/** Why and when a technology unlocked; {@code actor} is null for automatic unlocks. */
	public record Unlock(Instant at, String reason, java.util.UUID actor) {}

	private LuminaryId currentEra;
	private final Map<LuminaryId, Instant> eraEnteredAt = new LinkedHashMap<>();
	private final Map<LuminaryId, Unlock> unlocked = new TreeMap<>();
	private final Map<LuminaryId, Long> signals = new TreeMap<>();

	public Optional<LuminaryId> currentEra() {
		return Optional.ofNullable(currentEra);
	}

	public Map<LuminaryId, Instant> eraEnteredAt() {
		return Collections.unmodifiableMap(eraEnteredAt);
	}

	public Map<LuminaryId, Unlock> unlocked() {
		return Collections.unmodifiableMap(unlocked);
	}

	public boolean isUnlocked(LuminaryId technology) {
		return unlocked.containsKey(technology);
	}

	public Map<LuminaryId, Long> signals() {
		return Collections.unmodifiableMap(signals);
	}

	public long signal(LuminaryId id) {
		return signals.getOrDefault(id, 0L);
	}

	// package-private mutators: only ProgressionService and the codec write.

	void enterEra(LuminaryId era, Instant at) {
		currentEra = era;
		eraEnteredAt.putIfAbsent(era, at);
	}

	void putUnlock(LuminaryId technology, Unlock unlock) {
		unlocked.put(technology, unlock);
	}

	void putSignal(LuminaryId id, long value) {
		signals.put(id, value);
	}

	/** Restores a persisted state; used by the codec only. */
	public static final class Restore {
		private Restore() {
		}

		public static void era(ProgressionState s, LuminaryId era, Instant at) {
			s.eraEnteredAt.put(era, at);
		}

		public static void currentEra(ProgressionState s, LuminaryId era) {
			s.currentEra = era;
		}

		public static void unlock(ProgressionState s, LuminaryId tech, Unlock u) {
			s.unlocked.put(tech, u);
		}

		public static void signal(ProgressionState s, LuminaryId id, long value) {
			s.signals.put(id, value);
		}
	}
}
