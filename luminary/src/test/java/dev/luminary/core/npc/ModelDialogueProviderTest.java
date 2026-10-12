package dev.luminary.core.npc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.llm.LlmConfig;
import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmProvider;
import dev.luminary.core.npc.llm.LlmProviders;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.core.npc.llm.LlmResponse;
import dev.luminary.core.npc.llm.LlmStats;
import dev.luminary.core.npc.llm.MockLlmServer;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.core.story.Clue;
import dev.luminary.core.story.StoryController;
import dev.luminary.core.story.StoryDefinitions;
import dev.luminary.core.story.StoryPhase;
import dev.luminary.core.story.StoryState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model-backed provider: the prompt carries persona, memories and only the clues the story allows; the reply is
 * validated; every failure falls back to the scripted provider. The second half runs the real HTTP client against a
 * loopback Ollama look-alike through {@link NpcDialogueService}, so reveal and memory writes are exercised too.
 */
class ModelDialogueProviderTest {
	static final LuminaryId AI = LuminaryId.of("npc/ai"), SCRIPT = LuminaryId.of("dialogue/ai");
	static final LuminaryId P0 = LuminaryId.of("story/zero"), P1 = LuminaryId.of("story/one");
	static final LuminaryId HUM = LuminaryId.of("clue/hum"), LATER = LuminaryId.of("clue/later");
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes()), ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
	static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	StoryController story;
	NpcDefinitions defs;
	NpcRoster roster;
	List<String> diagnostics;
	MockLlmServer server;

	@BeforeEach
	void setUp() throws IOException {
		EventBus bus = new EventBus((e, l, t) -> { throw new AssertionError(t); });
		StoryDefinitions storyDefs = new StoryDefinitions()
				.addPhase(new StoryPhase(P0, 0, "t", Set.of(), false)).addPhase(new StoryPhase(P1, 1, "t", Set.of(), false))
				.addClue(new Clue(HUM, P0, "The ground hums at night.", Set.of()))
				.addClue(new Clue(LATER, P1, "clue.later", Set.of()));
		storyDefs.validate();
		story = new StoryController(new StoryState(), storyDefs, p -> false, bus, CLOCK, () -> {});
		story.start();
		defs = new NpcDefinitions()
				.addDialogue(new DialogueScript(SCRIPT, List.of(DialogueLine.plain("l.greet", 0))))
				.addNpc(new NpcDefinition(AI, "npc.ai", SCRIPT, List.of("You are Vesna, a cautious archivist.", "You never swear.")));
		defs.validate(storyDefs);
		roster = new NpcRoster();
		diagnostics = new ArrayList<>();
		server = new MockLlmServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	NpcDialogueService service(DialogueProvider p) {
		return new NpcDialogueService(defs, roster, story, () -> Optional.of(LuminaryId.of("era/stone")), p, CLOCK, () -> {}, Runnable::run);
	}

	ModelDialogueProvider model(LlmProvider llm) {
		return model(llm, Duration.ofMillis(800));
	}

	ModelDialogueProvider model(LlmProvider llm, Duration timeout) {
		return new ModelDialogueProvider(llm, new ScriptedDialogueProvider(), timeout, diagnostics::add);
	}

	static NpcDialogueService.Turn await(CompletableFuture<Optional<NpcDialogueService.Turn>> f) throws Exception {
		return f.get(5, TimeUnit.SECONDS).orElseThrow();
	}

	/** An in-memory model: answers with a fixed body, or fails, and records the prompt it was given. */
	static final class FakeLlm implements LlmProvider {
		final AtomicReference<LlmRequest> last = new AtomicReference<>();
		final LlmStats stats = new LlmStats();
		String reply;
		LlmException failure;
		boolean healthy = true;

		FakeLlm(String reply) { this.reply = reply; }

		@Override public String id() { return "fake"; }
		@Override public String model() { return "fake-1"; }
		@Override public boolean healthy() { return healthy; }
		@Override public LlmStats stats() { return stats; }

		@Override
		public CompletableFuture<LlmResponse> complete(LlmRequest request) {
			last.set(request);
			if (failure != null) {
				return CompletableFuture.failedFuture(failure);
			}
			if (reply == null) {
				return new CompletableFuture<>(); // never completes: exercises the timeout
			}
			Optional<JsonObject> json;
			try {
				json = Optional.of(JsonParser.parseString(reply).getAsJsonObject());
			} catch (RuntimeException e) {
				json = Optional.empty();
			}
			return CompletableFuture.completedFuture(new LlmResponse(reply, json, 1, id(), model()));
		}
	}

	@Test
	void promptCarriesPersonaMemoriesContextAndOnlyAllowedClues() throws Exception {
		FakeLlm llm = new FakeLlm("{\"line\":\"Welcome back.\",\"clue\":null,\"note\":null}");
		NpcDialogueService s = service(model(llm));
		s.memory(AI).add(MemoryEntry.of(MemoryKind.RELATIONSHIP, ALICE, "likes", "Alice likes oak forests", NOW, Provenance.PLAYER_SAID), NOW);
		await(s.talk(AI, ALICE, "Alice", Optional.of("Hello again, what about the oak forests?")));
		LlmRequest sent = llm.last.get();
		assertTrue(sent.jsonSchema().isPresent(), "structured output requested");
		String system = sent.messages().get(0).content();
		assertTrue(system.contains("Vesna, a cautious archivist"), "persona");
		assertTrue(system.contains("[RELATIONSHIP/PLAYER_SAID] Alice likes oak forests"), "retrieved memory with provenance");
		assertTrue(system.contains("current era: luminary:era/stone"), "public context");
		assertTrue(system.contains("story phase: luminary:story/zero"), "public context");
		assertTrue(system.contains("luminary:clue/hum: The ground hums at night."), "allowed clue of the current phase");
		assertFalse(system.contains("clue/later"), "clue of a later phase is not offered to the model");
		assertEquals("Alice: Hello again, what about the oak forests?", sent.messages().get(1).content());
	}

	@Test
	void validReplyBecomesLiteralLineAndAllowedClueIsRevealedThroughTheStory() throws Exception {
		FakeLlm llm = new FakeLlm("{\"line\":\"Listen: the ground hums at night.\",\"clue\":\"clue/hum\",\"note\":\"Alice is curious about sounds\"}");
		NpcDialogueService s = service(model(llm));
		NpcDialogueService.Turn t = await(s.talk(AI, ALICE, "Alice", Optional.of("Anything strange around here?")));
		assertTrue(t.fromProvider());
		assertEquals(DialogueResponse.LITERAL_KEY, t.textKey());
		assertEquals(Optional.of("Listen: the ground hums at night."), t.literalText());
		assertEquals(Optional.of(HUM), t.revealedClue());
		assertTrue(story.state().isRevealed(HUM));
		List<MemoryEntry> inferred = roster.get(AI).orElseThrow().memory().all().stream()
				.filter(m -> m.provenance() == Provenance.MODEL_INFERRED).toList();
		assertEquals(1, inferred.size());
		assertEquals(MemoryKind.UNVERIFIED_ASSUMPTION, inferred.get(0).kind());
		assertTrue(inferred.get(0).text().contains("Alice is curious about sounds"));
	}

	@Test
	void modelInferredNoteCannotOverrideAnObservedFact() throws Exception {
		FakeLlm llm = new FakeLlm("{\"line\":\"Ah, the woodcutter.\",\"clue\":null,\"note\":\"Alice is a miner\"}");
		NpcDialogueService s = service(model(llm));
		MemoryEntry fact = MemoryEntry.of(MemoryKind.RELATIONSHIP, ALICE, "job", "Alice is a woodcutter", NOW, Provenance.OBSERVED_IN_WORLD);
		s.memory(AI).add(fact, NOW);
		await(s.talk(AI, ALICE, "Alice"));
		List<MemoryEntry> live = roster.get(AI).orElseThrow().memory().all();
		assertTrue(live.stream().anyMatch(m -> m.text().equals("Alice is a woodcutter") && m.provenance() == Provenance.OBSERVED_IN_WORLD), "observed fact stays");
		assertTrue(live.stream().anyMatch(m -> m.provenance() == Provenance.MODEL_INFERRED), "guess is kept, marked low-trust");
	}

	@Test
	void clueOutsideTheAllowedSetIsDroppedButTheLineIsKept() throws Exception {
		FakeLlm llm = new FakeLlm("{\"line\":\"I know of something later.\",\"clue\":\"clue/later\"}");
		NpcDialogueService.Turn t = await(service(model(llm)).talk(AI, ALICE, "Alice"));
		assertEquals(Optional.of("I know of something later."), t.literalText());
		assertEquals(Optional.empty(), t.revealedClue());
		assertFalse(story.state().isRevealed(LATER));
		assertTrue(diagnostics.stream().anyMatch(d -> d.contains("outside the allowed set")));
	}

	@Test
	void overlongLineAndNoteAreBounded() throws Exception {
		String longLine = "a".repeat(1000), longNote = "b".repeat(1000);
		FakeLlm llm = new FakeLlm("{\"line\":\"" + longLine + "\",\"note\":\"" + longNote + "\"}");
		NpcDialogueService.Turn t = await(service(model(llm)).talk(AI, ALICE, "Alice"));
		assertEquals(ModelDialogueProvider.MAX_LINE_CHARS, t.literalText().orElseThrow().length());
		MemoryEntry note = roster.get(AI).orElseThrow().memory().ofKind(MemoryKind.UNVERIFIED_ASSUMPTION).get(0);
		assertTrue(note.text().length() <= MemoryEntry.MAX_TEXT);
	}

	@Test
	void unhealthyFailingInvalidAndSlowModelsFallBackToTheScript() throws Exception {
		FakeLlm unhealthy = new FakeLlm("{\"line\":\"never asked\"}");
		unhealthy.healthy = false;
		assertEquals("l.greet", await(service(model(unhealthy)).talk(AI, ALICE, "Alice")).textKey());
		assertEquals(null, unhealthy.last.get(), "an unhealthy model is not called");

		FakeLlm failing = new FakeLlm(null);
		failing.failure = new LlmException(LlmException.Kind.HTTP_STATUS, "500");
		assertEquals("l.greet", await(service(model(failing)).talk(AI, ALICE, "Alice")).textKey());

		FakeLlm garbage = new FakeLlm("I am not JSON at all");
		NpcDialogueService.Turn g = await(service(model(garbage)).talk(AI, ALICE, "Alice"));
		assertEquals("l.greet", g.textKey());
		assertTrue(g.fromProvider(), "the scripted fallback still counts as a provider line");
		assertEquals(Optional.empty(), g.literalText());

		FakeLlm slow = new FakeLlm(null); // never completes
		ModelDialogueProvider quick = new ModelDialogueProvider(slow, new ScriptedDialogueProvider(), Duration.ofMillis(100), diagnostics::add);
		assertEquals("l.greet", await(service(quick).talk(AI, ALICE, "Alice")).textKey());
		assertTrue(diagnostics.stream().anyMatch(d -> d.contains("TimeoutException")), diagnostics.toString());
	}

	@Test
	void endToEndThroughTheHttpClientAgainstALoopbackOllama() throws Exception {
		server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.ollamaBody(
				"{\"line\":\"The archive remembers you, Alice.\",\"clue\":\"clue/hum\",\"note\":null}"));
		LlmConfig cfg = LlmConfig.DISABLED.withProvider("ollama").withEndpoint(URI.create(server.url()))
				.withRequestTimeout(Duration.ofSeconds(4)).withMaxConcurrent(1);
		try (LlmProvider llm = LlmProviders.create(cfg, CLOCK)) {
			// generous timeouts: this test measures the contract, not loopback latency on a loaded CI runner
			NpcDialogueService s = service(model(llm, Duration.ofSeconds(4)));
			NpcDialogueService.Turn t = await(s.talk(AI, ALICE, "Alice", Optional.of("Do you remember me?")));
			assertEquals(Optional.of("The archive remembers you, Alice."), t.literalText());
			assertEquals(Optional.of(HUM), t.revealedClue());
			assertEquals(1, server.hits.get());
			JsonObject sent = JsonParser.parseString(server.seen.get(0).body()).getAsJsonObject();
			assertTrue(sent.has("format"), "schema forwarded to Ollama");
			assertTrue(sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("Vesna"));
			// the clue is public now: a second turn offers no clue and the model's repeat is refused without leaking
			server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.ollamaBody("{\"line\":\"Again: it hums.\",\"clue\":\"clue/hum\"}"));
			NpcDialogueService.Turn t2 = await(s.talk(AI, ALICE, "Alice"));
			assertEquals(Optional.empty(), t2.revealedClue());
			assertEquals(Optional.of("Again: it hums."), t2.literalText(), "line kept, clue dropped; diagnostics=" + diagnostics);
			assertTrue(diagnostics.stream().anyMatch(d -> d.contains("outside the allowed set")), diagnostics.toString());
		}
	}
}
