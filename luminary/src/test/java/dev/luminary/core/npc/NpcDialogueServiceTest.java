package dev.luminary.core.npc;

import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.Prerequisite;
import dev.luminary.core.story.Clue;
import dev.luminary.core.story.StoryBranch;
import dev.luminary.core.story.StoryCondition;
import dev.luminary.core.story.StoryController;
import dev.luminary.core.story.StoryDefinitions;
import dev.luminary.core.story.StoryPhase;
import dev.luminary.core.story.StoryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcDialogueServiceTest {
	static final LuminaryId AI = LuminaryId.of("npc/ai"), SCRIPT = LuminaryId.of("dialogue/ai");
	static final LuminaryId P0 = LuminaryId.of("story/zero"), P1 = LuminaryId.of("story/one");
	static final LuminaryId HUM = LuminaryId.of("clue/hum"), BRANCH = LuminaryId.of("story/branch/x");
	static final Prerequisite ORE = new Prerequisite.Signal(LuminaryId.of("signal/ore"), 1);
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes()), ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

	Set<Prerequisite> holding;
	StoryController story;
	NpcRoster roster;
	NpcDefinitions defs;
	AtomicInteger dirty;

	@BeforeEach
	void setUp() {
		EventBus bus = new EventBus((e, l, t) -> { throw new AssertionError(t); });
		holding = new HashSet<>();
		StoryDefinitions storyDefs = new StoryDefinitions().addBranch(new StoryBranch(BRANCH, "t"))
				.addPhase(new StoryPhase(P0, 0, "t", Set.of(), false)).addPhase(new StoryPhase(P1, 1, "t", Set.of(), false))
				.addClue(new Clue(HUM, P1, "clue.hum", Set.of()));
		storyDefs.validate();
		story = new StoryController(new StoryState(), storyDefs, holding::contains, bus, Clock.fixed(NOW, ZoneOffset.UTC), () -> {});
		story.start();
		defs = new NpcDefinitions()
				.addDialogue(new DialogueScript(SCRIPT, List.of(
						DialogueLine.plain("l.greet_a", 0),
						DialogueLine.plain("l.greet_b", 0),
						new DialogueLine("l.ore", 1, Set.of(new StoryCondition.Progression(ORE)), Optional.empty()),
						new DialogueLine("l.branch", 1, Set.of(new StoryCondition.BranchChosen(BRANCH)), Optional.empty()),
						new DialogueLine("clue.hum", 5, Set.of(), Optional.of(HUM)))))
				.addNpc(new NpcDefinition(AI, "npc.ai", SCRIPT));
		defs.validate(storyDefs);
		roster = new NpcRoster();
		dirty = new AtomicInteger();
	}

	NpcDialogueService service(DialogueProvider p) {
		return new NpcDialogueService(defs, roster, story, p, Clock.fixed(NOW, ZoneOffset.UTC), dirty::incrementAndGet);
	}

	@Test
	void greetingsRotatePerPlayerAndMemoryIsRecorded() {
		NpcDialogueService s = service(new ScriptedDialogueProvider());
		assertEquals("l.greet_a", s.talk(AI, ALICE, "Alice").orElseThrow().textKey());
		assertEquals("l.greet_b", s.talk(AI, ALICE, "Alice").orElseThrow().textKey());
		NpcDialogueService.Turn third = s.talk(AI, ALICE, "Alice").orElseThrow();
		assertEquals("l.greet_a", third.textKey());
		assertEquals(3, third.interactions());
		assertTrue(third.fromProvider());
		assertEquals(3, dirty.get());
	}

	@Test
	void conditionedLinesOutrankGreetingsWhenTheyHold() {
		NpcDialogueService s = service(new ScriptedDialogueProvider());
		holding.add(ORE);
		assertEquals("l.ore", s.talk(AI, ALICE, "Alice").orElseThrow().textKey());
		story.chooseBranch(BRANCH, ADMIN);
		// two priority-1 lines now: rotate between them
		assertEquals("l.branch", s.talk(AI, ALICE, "Alice").orElseThrow().textKey());
		assertEquals("l.ore", s.talk(AI, ALICE, "Alice").orElseThrow().textKey());
	}

	@Test
	void clueLineStaysLockedUntilStoryAllowsThenRevealsThroughTheController() {
		NpcDialogueService s = service(new ScriptedDialogueProvider());
		assertEquals("l.greet_a", s.talk(AI, ALICE, "Alice").orElseThrow().textKey(), "phase zero: clue of phase one is locked");
		assertFalse(story.state().isRevealed(HUM));
		story.setPhase(P1, ADMIN);
		NpcDialogueService.Turn turn = s.talk(AI, ALICE, "Alice").orElseThrow();
		assertEquals("clue.hum", turn.textKey());
		assertEquals(Optional.of(HUM), turn.revealedClue());
		assertTrue(story.state().isRevealed(HUM));
		// revealed clues are news no more: back to rotation
		assertEquals("l.greet_a", s.talk(AI, ALICE, "Alice").orElseThrow().textKey());
	}

	@Test
	void providerCannotLeakAClueTheStoryRefuses() {
		DialogueProvider rogue = new DialogueProvider() {
			@Override public String id() { return "rogue"; }
			@Override public Optional<DialogueResponse> respond(DialogueRequest r, LineGate g) {
				return Optional.of(new DialogueResponse("clue.hum", Optional.of(HUM), "rogue"));
			}
		};
		NpcDialogueService.Turn turn = service(rogue).talk(AI, ALICE, "Alice").orElseThrow();
		assertEquals(NpcDialogueService.FALLBACK_TEXT_KEY, turn.textKey());
		assertFalse(story.state().isRevealed(HUM));
		assertFalse(turn.fromProvider());
	}

	@Test
	void disabledAndThrowingProvidersFallBackSafely() {
		NpcDialogueService.Turn disabled = service(new DisabledDialogueProvider()).talk(AI, ALICE, "Alice").orElseThrow();
		assertEquals(NpcDialogueService.FALLBACK_TEXT_KEY, disabled.textKey());
		assertFalse(disabled.fromProvider());
		assertEquals(1, disabled.interactions(), "even silent meetings are remembered");
		DialogueProvider broken = new DialogueProvider() {
			@Override public String id() { return "broken"; }
			@Override public Optional<DialogueResponse> respond(DialogueRequest r, LineGate g) { throw new IllegalStateException("boom"); }
		};
		assertEquals(NpcDialogueService.FALLBACK_TEXT_KEY, service(broken).talk(AI, ALICE, "Alice").orElseThrow().textKey());
	}

	@Test
	void unknownNpcAndInvalidDefinitionsAreRejected() {
		assertTrue(service(new ScriptedDialogueProvider()).talk(LuminaryId.of("npc/ghost"), ALICE, "Alice").isEmpty());
		StoryDefinitions emptyStory = new StoryDefinitions();
		assertThrows(IllegalStateException.class, () -> new NpcDefinitions().addNpc(new NpcDefinition(AI, "n", SCRIPT)).validate(emptyStory));
		assertThrows(IllegalStateException.class, () -> new NpcDefinitions()
				.addDialogue(new DialogueScript(SCRIPT, List.of(new DialogueLine("x", 0, Set.of(), Optional.of(LuminaryId.of("clue/nope"))))))
				.addNpc(new NpcDefinition(AI, "n", SCRIPT)).validate(emptyStory));
		assertThrows(IllegalArgumentException.class, () -> new DialogueScript(SCRIPT, List.of()));
	}
}
