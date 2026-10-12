package dev.luminary.core.npc.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmProvider;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.core.npc.llm.LlmResponse;
import dev.luminary.core.npc.llm.LlmStats;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.TaskRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIntentPlannerTest {
	static final LuminaryId AI = LuminaryId.of("npc/ai");
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
	static final IntentCandidates CANDIDATES = new IntentCandidates(
			List.of(new IntentCandidates.Spot(10L, 1, 2, 3, 9.0), new IntentCandidates.Spot(11L, 7, 2, 3, 49.0)),
			List.of(new IntentCandidates.ContainerInfo(new IntentCandidates.Spot(20L, 4, 2, 4, 16.0), Map.of("minecraft:oak_log", 12))),
			Optional.of(new IntentCandidates.Spot(30L, 0, 2, 0, 4.0)), Map.of());

	final List<String> diagnostics = new ArrayList<>();

	static final class FakeLlm implements LlmProvider {
		final AtomicReference<LlmRequest> last = new AtomicReference<>();
		String reply;
		boolean healthy = true;
		LlmException failure;

		FakeLlm(String reply) { this.reply = reply; }
		@Override public String id() { return "fake"; }
		@Override public String model() { return "fake"; }
		@Override public boolean healthy() { return healthy; }
		@Override public LlmStats stats() { return new LlmStats(); }
		@Override public CompletableFuture<LlmResponse> complete(LlmRequest request) {
			last.set(request);
			if (failure != null) return CompletableFuture.failedFuture(failure);
			if (reply == null) return new CompletableFuture<>();
			Optional<JsonObject> json;
			try { json = Optional.of(JsonParser.parseString(reply).getAsJsonObject()); } catch (RuntimeException e) { json = Optional.empty(); }
			return CompletableFuture.completedFuture(new LlmResponse(reply, json, 1, id(), model()));
		}
	}

	IntentRequest req(String text, boolean busy) {
		return new IntentRequest(AI, ALICE, "Alice", text, List.of("A quiet woodsman."), List.of(), CANDIDATES, busy);
	}

	ModelIntentPlanner planner(FakeLlm llm) {
		return new ModelIntentPlanner(llm, new KeywordIntentPlanner(TaskRules.DEFAULTS), TaskRules.DEFAULTS, Duration.ofMillis(300), diagnostics::add);
	}

	static Optional<Intent> await(CompletableFuture<Optional<Intent>> f) throws Exception {
		return f.get(5, TimeUnit.SECONDS);
	}

	@Test
	void promptOffersIndicesNotCoordinatesAsTheOnlyVocabulary() throws Exception {
		FakeLlm llm = new FakeLlm("{\"intent\":\"none\",\"reply\":\"Hello.\"}");
		await(planner(llm).plan(req("hi there", false)));
		String system = llm.last.get().messages().get(0).content();
		assertTrue(system.contains("A quiet woodsman."));
		assertTrue(system.contains("- 0: 1 2 3") && system.contains("- 1: 7 2 3"), "logs listed by index");
		assertTrue(system.contains("- 0: 4 2 4, {minecraft:oak_log=12}"), "container with contents");
		assertTrue(system.contains("PLAYER POSITION: 0 2 0"));
		assertTrue(llm.last.get().jsonSchema().isPresent());
	}

	@Test
	void groundedIntentsBecomeActionsWithLiteralReplies() throws Exception {
		Intent gather = await(planner(new FakeLlm("{\"intent\":\"gather_wood\",\"log\":1,\"container\":0,\"count\":5,\"reply\":\"On my way.\"}")).plan(req("wood please", false))).orElseThrow();
		assertEquals(new NpcAction.GatherWood(11L, 20L, 5), gather.action().orElseThrow());
		assertEquals(Optional.of("On my way."), gather.literalReply());
		Intent withdraw = await(planner(new FakeLlm("{\"intent\":\"withdraw\",\"container\":0,\"item\":\"oak_log\",\"count\":500,\"reply\":\"Here.\"}")).plan(req("logs", false))).orElseThrow();
		assertEquals(new NpcAction.Withdraw(20L, "minecraft:oak_log", 64), withdraw.action().orElseThrow(), "bare id resolved, count clamped");
		Intent move = await(planner(new FakeLlm("{\"intent\":\"move_to\",\"reply\":\"Coming.\"}")).plan(req("come", false))).orElseThrow();
		assertEquals(new NpcAction.MoveTo(30L), move.action().orElseThrow());
		Intent deposit = await(planner(new FakeLlm("{\"intent\":\"deposit\",\"container\":0,\"reply\":\"Unloading.\"}")).plan(req("unload", false))).orElseThrow();
		assertEquals(new NpcAction.Deposit(20L), deposit.action().orElseThrow());
	}

	@Test
	void ungroundedChoicesAreDroppedButTheReplyStays() throws Exception {
		Intent badIndex = await(planner(new FakeLlm("{\"intent\":\"gather_wood\",\"log\":7,\"container\":3,\"count\":2,\"reply\":\"Sure.\"}")).plan(req("x", false))).orElseThrow();
		// out-of-range indices fall back to the nearest candidates rather than inventing positions
		assertEquals(new NpcAction.GatherWood(10L, 20L, 2), badIndex.action().orElseThrow());
		Intent badItem = await(planner(new FakeLlm("{\"intent\":\"withdraw\",\"container\":0,\"item\":\"minecraft:diamond\",\"count\":1,\"reply\":\"Diamonds!\"}")).plan(req("x", false))).orElseThrow();
		assertTrue(badItem.action().isEmpty());
		assertEquals(Optional.of("Diamonds!"), badItem.literalReply());
		assertTrue(diagnostics.stream().anyMatch(d -> d.contains("does not hold")));
		Intent unknown = await(planner(new FakeLlm("{\"intent\":\"build_castle\",\"reply\":\"Building!\"}")).plan(req("x", false))).orElseThrow();
		assertTrue(unknown.action().isEmpty());
		Intent busy = await(planner(new FakeLlm("{\"intent\":\"deposit\",\"container\":0,\"reply\":\"Unloading.\"}")).plan(req("unload", true))).orElseThrow();
		assertTrue(busy.action().isEmpty());
		assertEquals(Optional.of(Intent.REPLY_BUSY), busy.replyKey());
	}

	@Test
	void noneWithoutReplyAndAllFailuresFallBackToTheKeywordRules() throws Exception {
		assertTrue(await(planner(new FakeLlm("{\"intent\":\"none\",\"reply\":\"\"}")).plan(req("hello", false))).isEmpty(), "keyword rules see no work in hello");
		Intent viaRules = await(planner(new FakeLlm("not json")).plan(req("chop 2 logs", false))).orElseThrow();
		assertEquals(KeywordIntentPlanner.ID, viaRules.plannerId());
		assertEquals(new NpcAction.GatherWood(10L, 20L, 2), viaRules.action().orElseThrow());
		FakeLlm down = new FakeLlm("{}");
		down.healthy = false;
		assertEquals(KeywordIntentPlanner.ID, await(planner(down).plan(req("chop 2 logs", false))).orElseThrow().plannerId());
		FakeLlm failing = new FakeLlm(null);
		failing.failure = new LlmException(LlmException.Kind.HTTP_STATUS, "502");
		assertEquals(KeywordIntentPlanner.ID, await(planner(failing).plan(req("chop 2 logs", false))).orElseThrow().plannerId());
		FakeLlm slow = new FakeLlm(null);
		assertEquals(KeywordIntentPlanner.ID, await(planner(slow).plan(req("chop 2 logs", false))).orElseThrow().plannerId());
		assertFalse(diagnostics.isEmpty());
	}
}
