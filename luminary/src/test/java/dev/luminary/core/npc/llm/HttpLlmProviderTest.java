package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLlmProviderTest {
	/** A clock the test moves by hand, so cooldown expiry needs no sleeping. */
	static final class StepClock extends Clock {
		Instant now = Instant.parse("2026-10-12T00:00:00Z");
		@Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(java.time.ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
		void advance(Duration d) { now = now.plus(d); }
	}

	MockLlmServer server;
	StepClock clock;
	LlmRequest request;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockLlmServer();
		clock = new StepClock();
		request = LlmRequest.text(List.of(LlmMessage.system("be brief"), LlmMessage.user("hi")), 32, 0.3);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	LlmConfig config(String provider) {
		return LlmConfig.DISABLED.withProvider(provider).withEndpoint(URI.create(server.url()))
				.withRequestTimeout(Duration.ofMillis(800)).withMaxConcurrent(1);
	}

	static <T> T await(CompletableFuture<T> f) throws Exception {
		return f.get(5, TimeUnit.SECONDS);
	}

	static LlmException failure(CompletableFuture<?> f) {
		Exception e = assertThrows(Exception.class, () -> f.get(5, TimeUnit.SECONDS));
		Throwable c = e.getCause() != null ? e.getCause() : e;
		assertTrue(c instanceof LlmException, "expected LlmException, got " + c);
		return (LlmException) c;
	}

	@Test
	void ollamaRequestAndResponseWireFormat() throws Exception {
		server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.ollamaBody("pong"));
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			LlmResponse r = await(p.complete(request));
			assertEquals("pong", r.text());
			assertEquals("ollama", r.providerId());
			MockLlmServer.Seen seen = server.seen.get(0);
			assertEquals("/api/chat", seen.path());
			JsonObject sent = JsonParser.parseString(seen.body()).getAsJsonObject();
			assertEquals("qwen2.5:7b", sent.get("model").getAsString());
			assertFalse(sent.get("stream").getAsBoolean());
			assertEquals(2, sent.getAsJsonArray("messages").size());
			assertEquals("system", sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
			assertEquals(32, sent.getAsJsonObject("options").get("num_predict").getAsInt());
			assertFalse(seen.headers().containsKey("Authorization"));
			assertEquals(1, p.stats().successes());
		}
	}

	@Test
	void ollamaStructuredOutputIsParsedAndSchemaForwarded() throws Exception {
		server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.ollamaBody("{\"action\":\"gather_wood\",\"maxLogs\":3}"));
		JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"required\":[\"action\"]}").getAsJsonObject();
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			LlmResponse r = await(p.complete(LlmRequest.structured(request.messages(), schema, 64)));
			assertEquals("gather_wood", r.structured().orElseThrow().get("action").getAsString());
			JsonObject sent = JsonParser.parseString(server.seen.get(0).body()).getAsJsonObject();
			assertEquals("object", sent.getAsJsonObject("format").get("type").getAsString());
		}
	}

	@Test
	void openAiWireFormatBearerAuthAndErrorBody() throws Exception {
		server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.openAiBody("hello there"));
		LlmConfig cfg = config("openai").withEndpoint(URI.create(server.url() + "/v1")).withApiKey(Optional.of("sk-test-not-a-real-key"));
		try (LlmProvider p = LlmProviders.create(cfg, clock)) {
			LlmResponse r = await(p.complete(request));
			assertEquals("hello there", r.text());
			MockLlmServer.Seen seen = server.seen.get(0);
			assertEquals("/v1/chat/completions", seen.path());
			assertEquals("Bearer sk-test-not-a-real-key", seen.headers().get("Authorization").get(0));
			JsonObject sent = JsonParser.parseString(seen.body()).getAsJsonObject();
			assertEquals(32, sent.get("max_tokens").getAsInt());
			assertFalse(sent.has("response_format"));

			server.handler = body -> MockLlmServer.Reply.ok("{\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\"}}");
			assertEquals(LlmException.Kind.REFUSED, failure(p.complete(request)).kind());
		}
	}

	@Test
	void openAiStructuredRequestsAskForJsonObject() throws Exception {
		server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.openAiBody("```json\n{\"ok\":true}\n```"));
		JsonObject schema = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();
		try (LlmProvider p = LlmProviders.create(config("openai"), clock)) {
			LlmResponse r = await(p.complete(LlmRequest.structured(request.messages(), schema, 16)));
			assertTrue(r.structured().orElseThrow().get("ok").getAsBoolean(), "fenced JSON is extracted");
			JsonObject sent = JsonParser.parseString(server.seen.get(0).body()).getAsJsonObject();
			assertEquals("json_object", sent.getAsJsonObject("response_format").get("type").getAsString());
			assertEquals(3, sent.getAsJsonArray("messages").size(), "the schema is spelled out in an extra system message");
		}
	}

	@Test
	void timeoutFailsWithoutBlockingAndStartsCooldown() throws Exception {
		server.handler = body -> new MockLlmServer.Reply(200, MockLlmServer.ollamaBody("late"), 3000);
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			long before = System.nanoTime();
			CompletableFuture<LlmResponse> f = p.complete(request);
			assertTrue((System.nanoTime() - before) < TimeUnit.MILLISECONDS.toNanos(200), "complete() must return immediately");
			assertEquals(LlmException.Kind.TIMEOUT, failure(f).kind());
			assertFalse(p.healthy(), "a transport failure starts a cooldown");
			assertEquals(LlmException.Kind.UNAVAILABLE, failure(p.complete(request)).kind());
			assertEquals(1, p.stats().rejectedCooldown());
			clock.advance(Duration.ofSeconds(6));
			assertTrue(p.healthy(), "the first cooldown is 5 s");
		}
	}

	@Test
	void cooldownDoublesAndClearsAfterSuccess() throws Exception {
		server.handler = body -> new MockLlmServer.Reply(500, "{\"error\":\"boom\"}", 0);
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			assertEquals(LlmException.Kind.HTTP_STATUS, failure(p.complete(request)).kind());
			clock.advance(Duration.ofSeconds(6));
			assertEquals(LlmException.Kind.HTTP_STATUS, failure(p.complete(request)).kind());
			clock.advance(Duration.ofSeconds(6));
			assertFalse(p.healthy(), "second failure cools down for 10 s");
			clock.advance(Duration.ofSeconds(5));
			assertTrue(p.healthy());
			server.handler = body -> MockLlmServer.Reply.ok(MockLlmServer.ollamaBody("ok"));
			await(p.complete(request));
			server.handler = body -> new MockLlmServer.Reply(500, "x", 0);
			failure(p.complete(request));
			clock.advance(Duration.ofSeconds(6));
			assertTrue(p.healthy(), "a success resets the backoff to 5 s");
		}
	}

	@Test
	void oversizedAndMalformedBodiesAreRejectedWithoutCooldownForMalformed() throws Exception {
		server.handler = body -> MockLlmServer.Reply.ok("{\"message\":{\"content\":\"" + "x".repeat(70_000) + "\"}}");
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			assertEquals(LlmException.Kind.TOO_LARGE, failure(p.complete(request)).kind());
		}
		server.handler = body -> MockLlmServer.Reply.ok("not json at all");
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			assertEquals(LlmException.Kind.BAD_RESPONSE, failure(p.complete(request)).kind());
			assertTrue(p.healthy(), "a malformed reply is not an outage");
		}
	}

	@Test
	void concurrencyLimitFailsFastWithBusy() throws Exception {
		server.handler = body -> new MockLlmServer.Reply(200, MockLlmServer.ollamaBody("slow"), 400);
		try (LlmProvider p = LlmProviders.create(config("ollama"), clock)) {
			CompletableFuture<LlmResponse> first = p.complete(request);
			assertEquals(LlmException.Kind.BUSY, failure(p.complete(request)).kind());
			assertEquals("slow", await(first).text());
			assertEquals(1, p.stats().rejectedBusy());
		}
	}

	@Test
	void endpointMustBeLoopbackOrAllowListed() {
		LlmConfig remote = config("ollama").withEndpoint(URI.create("http://example.invalid:11434"));
		assertThrows(IllegalArgumentException.class, () -> LlmProviders.create(remote, clock));
		LlmConfig allowed = remote.withAllowedHosts(Set.of("example.invalid"));
		try (LlmProvider p = LlmProviders.create(allowed, clock)) {
			assertNotNull(p);
		}
		assertThrows(IllegalArgumentException.class, () -> LlmConfig.DISABLED.withEndpoint(URI.create("file:///etc/passwd")));
		assertThrows(IllegalArgumentException.class, () -> LlmProviders.create(config("mystery"), clock));
	}

	@Test
	void noneProviderFailsImmediatelyAndIsUnhealthy() {
		LlmProvider p = LlmProviders.create(LlmConfig.DISABLED, clock);
		assertEquals("none", p.id());
		assertFalse(p.healthy());
		assertEquals(LlmException.Kind.UNAVAILABLE, failure(p.complete(request)).kind());
	}

	@Test
	void jsonExtractorHandlesBareFencedAndEmbeddedObjects() {
		assertTrue(JsonExtractor.extractObject("{\"a\":1}").isPresent());
		assertEquals(2, JsonExtractor.extractObject("Sure! ```json\n{\"a\":2}\n``` done").orElseThrow().get("a").getAsInt());
		assertEquals(3, JsonExtractor.extractObject("Here it is: {\"a\":3} hope that helps").orElseThrow().get("a").getAsInt());
		assertTrue(JsonExtractor.extractObject("no json here").isEmpty());
		assertTrue(JsonExtractor.extractObject("[1,2,3]").isEmpty());
		assertTrue(JsonExtractor.extractObject(null).isEmpty());
	}

	@Test
	void requestArgumentsAreBounded() {
		assertThrows(IllegalArgumentException.class, () -> LlmRequest.text(List.of(), 10, 0.5));
		assertThrows(IllegalArgumentException.class, () -> LlmRequest.text(List.of(LlmMessage.user("x")), 0, 0.5));
		assertThrows(IllegalArgumentException.class, () -> LlmRequest.text(List.of(LlmMessage.user("x")), 10, 3.0));
	}
}
