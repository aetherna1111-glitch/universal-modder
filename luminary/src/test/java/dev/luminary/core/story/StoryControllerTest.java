package dev.luminary.core.story;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.Prerequisite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryControllerTest {
	static final LuminaryId P0 = LuminaryId.of("story/zero"), P1 = LuminaryId.of("story/first_trace"), P2 = LuminaryId.of("story/deviation"), P3 = LuminaryId.of("story/record");
	static final LuminaryId ORE_VERIFIED_SIGNAL = LuminaryId.of("signal/ore");
	static final LuminaryId BRANCH = LuminaryId.of("story/branch/containment");
	static final LuminaryId C1 = LuminaryId.of("clue/hum"), C2 = LuminaryId.of("clue/compass");
	static final UUID ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

	EventBus bus;
	Set<Prerequisite> holding;
	StoryController story;
	AtomicInteger dirty;

	@BeforeEach
	void setUp() {
		bus = new EventBus((e, l, t) -> { throw new AssertionError(t); });
		holding = new HashSet<>();
		dirty = new AtomicInteger();
		StoryDefinitions defs = new StoryDefinitions()
				.addBranch(new StoryBranch(BRANCH, "t"))
				.addPhase(new StoryPhase(P0, 0, "t", Set.of(), false))
				.addPhase(new StoryPhase(P1, 1, "t", Set.of(new StoryCondition.Progression(new Prerequisite.Signal(ORE_VERIFIED_SIGNAL, 1))), true))
				.addPhase(new StoryPhase(P2, 2, "t", Set.of(new StoryCondition.ClueRevealed(C1)), true))
				.addPhase(new StoryPhase(P3, 3, "t", Set.of(new StoryCondition.BranchChosen(BRANCH)), false))
				.addClue(new Clue(C1, P1, "t", Set.of()))
				.addClue(new Clue(C2, P2, "t", Set.of(new StoryCondition.ClueRevealed(C1))));
		defs.validate();
		story = new StoryController(new StoryState(), defs, holding::contains, bus, Clock.fixed(NOW, ZoneOffset.UTC), dirty::incrementAndGet);
		story.start();
	}

	@Test
	void startsInFirstPhaseAndNothingIsRevealable() {
		assertEquals(P0, story.state().currentPhase().orElseThrow());
		assertEquals("first_phase", story.state().history().get(0).reason());
		assertFalse(story.mayReveal(C1));
		assertEquals(StoryController.REASON_CLUE_LOCKED, story.reveal(C1, "npc").reasonKey());
		assertTrue(story.revealable().isEmpty());
	}

	@Test
	void autoAdvanceHappensOnlyForOptedInPhasesWhoseConditionsHold() {
		holding.add(new Prerequisite.Signal(ORE_VERIFIED_SIGNAL, 1));
		bus.publish(new DomainEvent.SignalChanged(NOW, ORE_VERIFIED_SIGNAL, 0, 1, "test"));   // what ProgressionService would publish
		assertEquals(P1, story.state().currentPhase().orElseThrow());
		assertEquals("conditions_met", story.state().history().get(1).reason());
		// P2 needs clue C1 revealed; revealing it (now allowed) cascades into P2 automatically
		assertTrue(story.mayReveal(C1));
		assertTrue(story.reveal(C1, "npc").ok());
		assertEquals(P2, story.state().currentPhase().orElseThrow());
		// P3 is admin-only: even with the branch chosen it does not auto-advance
		assertTrue(story.chooseBranch(BRANCH, ADMIN).ok());
		assertEquals(P2, story.state().currentPhase().orElseThrow());
		assertTrue(story.holds(new StoryCondition.BranchChosen(BRANCH)));
	}

	@Test
	void adminSetsPhasesForwardAndBackwardWithRecords() {
		assertEquals(StoryController.REASON_UNKNOWN_PHASE, story.setPhase(LuminaryId.of("story/nope"), ADMIN).reasonKey());
		assertEquals(StoryController.REASON_SAME_PHASE, story.setPhase(P0, ADMIN).reasonKey());
		assertTrue(story.setPhase(P3, ADMIN).ok());
		assertEquals(P3, story.state().currentPhase().orElseThrow());
		assertTrue(story.setPhase(P1, ADMIN).ok());
		StoryState.PhaseChange last = story.state().history().get(story.state().history().size() - 1);
		assertEquals(P3, last.from());
		assertEquals(P1, last.to());
		assertEquals("admin", last.reason());
		assertEquals(ADMIN, last.actor());
		assertEquals(NOW, story.state().phaseEnteredAt().get(P3));
	}

	@Test
	void cluesRespectPhaseAndConditionsAndRevealIsIdempotentWithoutOverride() {
		story.setPhase(P2, ADMIN);
		assertTrue(story.mayReveal(C1));
		assertFalse(story.mayReveal(C2), "C2 needs C1 revealed first even though its phase is reached");
		assertEquals(1, story.revealable().size());
		assertTrue(story.reveal(C1, "npc").ok());
		assertEquals(StoryController.REASON_ALREADY_REVEALED, story.reveal(C1, "npc").reasonKey());
		assertTrue(story.mayReveal(C2));
		assertTrue(story.reveal(C2, "book").ok());
		assertEquals(2, story.revealedClues().size());
		assertEquals(StoryController.REASON_UNKNOWN_CLUE, story.reveal(LuminaryId.of("clue/nope"), "npc").reasonKey());
		assertTrue(bus.history().stream().anyMatch(e -> e instanceof DomainEvent.ClueRevealed r && r.source().equals("book")));
	}

	@Test
	void branchChangesAreRecordedAndIdempotent() {
		assertEquals(StoryController.REASON_UNKNOWN_BRANCH, story.chooseBranch(LuminaryId.of("story/branch/nope"), ADMIN).reasonKey());
		Result first = story.chooseBranch(BRANCH, ADMIN);
		assertTrue(first.ok());
		assertEquals(StoryController.REASON_SAME_BRANCH, story.chooseBranch(BRANCH, ADMIN).reasonKey());
		assertEquals(BRANCH, story.state().branch().orElseThrow());
	}

	@Test
	void definitionsAreValidated() {
		assertThrows(IllegalStateException.class, () -> new StoryDefinitions().addPhase(new StoryPhase(P0, 0, "t", Set.of(), false)).addPhase(new StoryPhase(P1, 0, "t", Set.of(), false)).validate());
		assertThrows(IllegalStateException.class, () -> new StoryDefinitions().addPhase(new StoryPhase(P0, 0, "t", Set.of(new StoryCondition.PhaseAtLeast(P0)), false)).validate());
		assertThrows(IllegalStateException.class, () -> new StoryDefinitions().addPhase(new StoryPhase(P0, 0, "t", Set.of(), false)).addClue(new Clue(C1, P1, "t", Set.of())).validate());
		assertThrows(IllegalStateException.class, () -> new StoryDefinitions().addPhase(new StoryPhase(P0, 0, "t", Set.of(), false))
				.addClue(new Clue(C1, P0, "t", Set.of(new StoryCondition.ClueRevealed(C2)))).addClue(new Clue(C2, P0, "t", Set.of(new StoryCondition.ClueRevealed(C1)))).validate());
		assertThrows(IllegalStateException.class, () -> new StoryDefinitions().addPhase(new StoryPhase(P0, 0, "t", Set.of(), false))
				.addClue(new Clue(C1, P0, "t", Set.of(new StoryCondition.BranchChosen(BRANCH)))).validate());
		assertThrows(IllegalArgumentException.class, () -> new Clue(C1, P0, "t", Set.of(new StoryCondition.ClueRevealed(C1))));
	}
}
