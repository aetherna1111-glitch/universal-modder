package dev.luminary.core.story;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.Prerequisite;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Narrative control. The only writers of story state are administrators (through {@link #setPhase},
 * {@link #chooseBranch}, {@link #reveal}) and the data-driven auto-advance for phases that opt in. Nothing here is
 * reachable from the language-model layer: the NPC asks {@link #mayReveal} and {@link #revealedClues} read-only.
 */
public final class StoryController {
	public static final String REASON_UNKNOWN_PHASE = "luminary.story.unknown_phase";
	public static final String REASON_SAME_PHASE = "luminary.story.same_phase";
	public static final String REASON_UNKNOWN_BRANCH = "luminary.story.unknown_branch";
	public static final String REASON_SAME_BRANCH = "luminary.story.same_branch";
	public static final String REASON_UNKNOWN_CLUE = "luminary.story.unknown_clue";
	public static final String REASON_CLUE_LOCKED = "luminary.story.clue_locked";
	public static final String REASON_ALREADY_REVEALED = "luminary.story.already_revealed";

	private final StoryState state;
	private final StoryDefinitions definitions;
	private final Predicate<Prerequisite> progression;
	private final EventBus events;
	private final Clock clock;
	private final Runnable onChange;
	private boolean evaluating;

	/**
	 * @param progression evaluates progression prerequisites (in production {@code ProgressionService::holds}).
	 */
	public StoryController(StoryState state, StoryDefinitions definitions, Predicate<Prerequisite> progression, EventBus events,
						   Clock clock, Runnable onChange) {
		this.state = Objects.requireNonNull(state);
		this.definitions = Objects.requireNonNull(definitions);
		this.progression = Objects.requireNonNull(progression);
		this.events = Objects.requireNonNull(events);
		this.clock = Objects.requireNonNull(clock);
		this.onChange = Objects.requireNonNull(onChange);
	}

	/** Enters the first phase on a fresh world and subscribes auto-advance to the events that can change conditions. */
	public void start() {
		events.subscribe(DomainEvent.DiscoveryStatusChanged.class, 200, e -> evaluateAutoAdvance());
		events.subscribe(DomainEvent.TechnologyUnlocked.class, 200, e -> evaluateAutoAdvance());
		events.subscribe(DomainEvent.EraEntered.class, 200, e -> evaluateAutoAdvance());
		events.subscribe(DomainEvent.SignalChanged.class, 200, e -> evaluateAutoAdvance());
		events.subscribe(DomainEvent.StoryBranchChosen.class, 200, e -> evaluateAutoAdvance());
		events.subscribe(DomainEvent.ClueRevealed.class, 200, e -> evaluateAutoAdvance());
		if (state.currentPhase().isEmpty() || definitions.phase(state.currentPhase().get()).isEmpty()) {
			definitions.firstPhase().ifPresent(first -> transition(first.id(), "first_phase", null));
		}
		evaluateAutoAdvance();
	}

	public StoryState state() {
		return state;
	}

	public StoryDefinitions definitions() {
		return definitions;
	}

	// ------------------------------------------------------------------ conditions

	public boolean holds(StoryCondition c) {
		if (c instanceof StoryCondition.Progression p) {
			return progression.test(p.prerequisite());
		}
		if (c instanceof StoryCondition.BranchChosen b) {
			return state.branch().map(b.branch()::equals).orElse(false);
		}
		if (c instanceof StoryCondition.ClueRevealed r) {
			return state.isRevealed(r.clue());
		}
		if (c instanceof StoryCondition.PhaseAtLeast p) {
			return phaseOrder(state.currentPhase().orElse(null)) >= phaseOrder(p.phase());
		}
		throw new IllegalStateException("unknown story condition " + c);
	}

	private int phaseOrder(LuminaryId phase) {
		if (phase == null) {
			return Integer.MIN_VALUE;
		}
		return definitions.phase(phase).map(StoryPhase::order).orElse(Integer.MIN_VALUE);
	}

	// ------------------------------------------------------------------ phases

	/** Administrative phase change, forwards or backwards; always recorded with the actor. */
	public Result setPhase(LuminaryId phase, UUID admin) {
		if (definitions.phase(phase).isEmpty()) {
			return Result.fail(REASON_UNKNOWN_PHASE);
		}
		if (state.currentPhase().map(phase::equals).orElse(false)) {
			return Result.fail(REASON_SAME_PHASE);
		}
		transition(phase, "admin", admin);
		evaluateAutoAdvance();
		return Result.OK;
	}

	/**
	 * Advances to the next phase only while that phase is marked {@code autoAdvance} and its conditions hold.
	 * @return phases entered in this call
	 */
	public List<LuminaryId> evaluateAutoAdvance() {
		if (evaluating) {
			return List.of();
		}
		evaluating = true;
		List<LuminaryId> entered = new ArrayList<>();
		try {
			int guard = 0;
			while (guard++ < 1000) {
				Optional<LuminaryId> current = state.currentPhase();
				if (current.isEmpty()) {
					break;
				}
				Optional<StoryPhase> next = definitions.phaseAfter(current.get());
				if (next.isEmpty() || !next.get().autoAdvance() || !next.get().activationConditions().stream().allMatch(this::holds)) {
					break;
				}
				transition(next.get().id(), "conditions_met", null);
				entered.add(next.get().id());
			}
		} finally {
			evaluating = false;
		}
		return entered;
	}

	/** Activation conditions of {@code phase} that do not hold yet. */
	public List<StoryCondition> missing(StoryPhase phase) {
		List<StoryCondition> out = new ArrayList<>();
		for (StoryCondition c : phase.activationConditions()) {
			if (!holds(c)) {
				out.add(c);
			}
		}
		return out;
	}

	private void transition(LuminaryId to, String reason, UUID actor) {
		Instant now = clock.instant();
		LuminaryId from = state.currentPhase().orElse(null);
		state.setPhase(to, now, reason, actor);
		onChange.run();
		events.publish(new DomainEvent.StoryPhaseChanged(now, from, to, actor, reason));
	}

	// ------------------------------------------------------------------ branches

	public Result chooseBranch(LuminaryId branch, UUID admin) {
		if (definitions.branch(branch).isEmpty()) {
			return Result.fail(REASON_UNKNOWN_BRANCH);
		}
		if (state.branch().map(branch::equals).orElse(false)) {
			return Result.fail(REASON_SAME_BRANCH);
		}
		LuminaryId previous = state.branch().orElse(null);
		state.setBranch(branch);
		onChange.run();
		events.publish(new DomainEvent.StoryBranchChosen(clock.instant(), previous, branch, admin));
		evaluateAutoAdvance();
		return Result.OK;
	}

	// ------------------------------------------------------------------ clues

	/** True when the clue exists, its phase has been reached and all its conditions hold. Read-only; safe for the NPC. */
	public boolean mayReveal(LuminaryId clueId) {
		Optional<Clue> clue = definitions.clue(clueId);
		if (clue.isEmpty()) {
			return false;
		}
		if (phaseOrder(state.currentPhase().orElse(null)) < phaseOrder(clue.get().phase())) {
			return false;
		}
		return clue.get().conditions().stream().allMatch(this::holds);
	}

	/** Clues that may be revealed now but have not been yet. */
	public List<Clue> revealable() {
		List<Clue> out = new ArrayList<>();
		for (Clue c : definitions.clues()) {
			if (!state.isRevealed(c.id()) && mayReveal(c.id())) {
				out.add(c);
			}
		}
		return out;
	}

	public List<Clue> revealedClues() {
		List<Clue> out = new ArrayList<>();
		for (LuminaryId id : state.revealedClues().keySet()) {
			definitions.clue(id).ifPresent(out::add);
		}
		return out;
	}

	/**
	 * Records that a clue was surfaced to players. Refused while {@link #mayReveal} is false, whoever asks: there is no
	 * override, because a clue leaked early cannot be taken back. {@code source} names the channel (npc, book, admin).
	 */
	public Result reveal(LuminaryId clueId, String source) {
		if (definitions.clue(clueId).isEmpty()) {
			return Result.fail(REASON_UNKNOWN_CLUE);
		}
		if (state.isRevealed(clueId)) {
			return Result.fail(REASON_ALREADY_REVEALED);
		}
		if (!mayReveal(clueId)) {
			return Result.fail(REASON_CLUE_LOCKED);
		}
		Instant now = clock.instant();
		state.reveal(clueId, now);
		onChange.run();
		events.publish(new DomainEvent.ClueRevealed(now, clueId, source));
		evaluateAutoAdvance();
		return Result.OK;
	}
}
