package dev.luminary.core.story;

import dev.luminary.core.ids.LuminaryId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persisted story state: phase, branch, revealed clues and the full history of phase changes. */
public final class StoryState {
	/** One phase transition; {@code actor} is null for automatic activation. */
	public record PhaseChange(LuminaryId from, LuminaryId to, Instant at, String reason, UUID actor) {}

	private LuminaryId currentPhase;
	private LuminaryId branch;
	private final Map<LuminaryId, Instant> phaseEnteredAt = new LinkedHashMap<>();
	private final Map<LuminaryId, Instant> revealedClues = new LinkedHashMap<>();
	private final List<PhaseChange> history = new ArrayList<>();

	public Optional<LuminaryId> currentPhase() {
		return Optional.ofNullable(currentPhase);
	}

	public Optional<LuminaryId> branch() {
		return Optional.ofNullable(branch);
	}

	public Map<LuminaryId, Instant> phaseEnteredAt() {
		return Collections.unmodifiableMap(phaseEnteredAt);
	}

	public Map<LuminaryId, Instant> revealedClues() {
		return Collections.unmodifiableMap(revealedClues);
	}

	public boolean isRevealed(LuminaryId clue) {
		return revealedClues.containsKey(clue);
	}

	public List<PhaseChange> history() {
		return Collections.unmodifiableList(history);
	}

	void setPhase(LuminaryId phase, Instant at, String reason, UUID actor) {
		history.add(new PhaseChange(currentPhase, phase, at, reason, actor));
		currentPhase = phase;
		phaseEnteredAt.putIfAbsent(phase, at);
	}

	void setBranch(LuminaryId b) {
		branch = b;
	}

	void reveal(LuminaryId clue, Instant at) {
		revealedClues.putIfAbsent(clue, at);
	}

	/** Restores persisted values; codec only. */
	public static final class Restore {
		private Restore() {
		}

		public static void currentPhase(StoryState s, LuminaryId phase) {
			s.currentPhase = phase;
		}

		public static void branch(StoryState s, LuminaryId branch) {
			s.branch = branch;
		}

		public static void phaseEntered(StoryState s, LuminaryId phase, Instant at) {
			s.phaseEnteredAt.put(phase, at);
		}

		public static void revealed(StoryState s, LuminaryId clue, Instant at) {
			s.revealedClues.put(clue, at);
		}

		public static void history(StoryState s, PhaseChange change) {
			s.history.add(change);
		}
	}
}
