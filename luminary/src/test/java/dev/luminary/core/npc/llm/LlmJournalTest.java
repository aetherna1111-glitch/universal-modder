package dev.luminary.core.npc.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmJournalTest {
	@Test
	void writesOneJsonLinePerRequestWithoutContentByDefault(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("logs").resolve("llm.jsonl");
		LlmJournal journal = new LlmJournal(file, 1 << 20, 2, false, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), m -> { throw new AssertionError(m); });
		LlmRequest req = LlmRequest.text(List.of(LlmMessage.user("secret player chat")), 16, 0.5);
		journal.record("ollama", "m", req, new LlmResponse("hello", Optional.empty(), 123, "ollama", "m"), null, "dialogue");
		journal.record("ollama", "m", req, null, new LlmException(LlmException.Kind.TIMEOUT, "slow"), "dialogue");
		List<String> lines = Files.readAllLines(file);
		assertEquals(2, lines.size());
		assertTrue(lines.get(0).contains("\"ok\":true") && lines.get(0).contains("\"latencyMs\":123"));
		assertTrue(lines.get(1).contains("\"error\":\"TIMEOUT\""));
		assertFalse(String.join("", lines).contains("secret player chat"), "prompt text stays out of the journal unless logContent is on");
	}

	@Test
	void rotatesAtTheSizeLimitKeepingArchives(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("llm.jsonl");
		LlmJournal journal = new LlmJournal(file, 300, 2, true, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), m -> { throw new AssertionError(m); });
		LlmRequest req = LlmRequest.text(List.of(LlmMessage.user("x".repeat(100))), 16, 0.5);
		for (int i = 0; i < 6; i++) {
			journal.record("ollama", "m", req, new LlmResponse("y", Optional.empty(), 1, "ollama", "m"), null, "test");
		}
		assertTrue(Files.exists(file));
		assertTrue(Files.exists(dir.resolve("llm.jsonl.1")));
		assertTrue(Files.exists(dir.resolve("llm.jsonl.2")));
		assertFalse(Files.exists(dir.resolve("llm.jsonl.3")), "only two archives are kept");
		assertTrue(Files.readString(file).contains("\"prompt\""), "logContent=true records the prompt");
	}
}
