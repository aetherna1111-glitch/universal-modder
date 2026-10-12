package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.function.Consumer;

/**
 * UTF-8 JSONL journal of model requests: one line per request with timing, sizes and outcome, never the prompt or
 * reply text unless {@code logContent} is on. Rotates at {@code maxBytes} keeping {@code archives} old files.
 * Writes are synchronized and small; callers invoke it from provider threads.
 */
public final class LlmJournal {
	private final Path file;
	private final long maxBytes;
	private final int archives;
	private final boolean logContent;
	private final Clock clock;
	private final Consumer<String> onError;

	public LlmJournal(Path file, long maxBytes, int archives, boolean logContent, Clock clock, Consumer<String> onError) {
		this.file = file;
		this.maxBytes = maxBytes;
		this.archives = archives;
		this.logContent = logContent;
		this.clock = clock;
		this.onError = onError;
	}

	public synchronized void record(String providerId, String model, LlmRequest request, LlmResponse response, LlmException error, String purpose) {
		JsonObject o = new JsonObject();
		o.addProperty("at", clock.instant().toString());
		o.addProperty("provider", providerId);
		o.addProperty("model", model);
		o.addProperty("purpose", purpose);
		o.addProperty("promptChars", request.promptChars());
		o.addProperty("structured", request.jsonSchema().isPresent());
		if (response != null) {
			o.addProperty("ok", true);
			o.addProperty("latencyMs", response.latencyMillis());
			o.addProperty("responseChars", response.text().length());
			o.addProperty("parsedJson", response.structured().isPresent());
			if (logContent) {
				o.addProperty("response", response.text());
			}
		} else {
			o.addProperty("ok", false);
			o.addProperty("error", error == null ? "unknown" : error.kind().name());
			o.addProperty("message", error == null ? "" : String.valueOf(error.getMessage()));
		}
		if (logContent) {
			StringBuilder prompt = new StringBuilder();
			request.messages().forEach(m -> prompt.append(m.wireRole()).append(": ").append(m.content()).append('\n'));
			o.addProperty("prompt", prompt.toString());
		}
		append(o.toString());
	}

	private void append(String line) {
		try {
			Files.createDirectories(file.getParent());
			if (Files.exists(file) && Files.size(file) >= maxBytes) {
				rotate();
			}
			Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			onError.accept("llm journal write failed: " + e.getMessage());
		}
	}

	private void rotate() throws IOException {
		for (int i = archives - 1; i >= 1; i--) {
			Path from = archive(i);
			if (Files.exists(from)) {
				Files.move(from, archive(i + 1), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		if (archives >= 1) {
			Files.move(file, archive(1), StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.delete(file);
		}
	}

	private Path archive(int n) {
		return file.resolveSibling(file.getFileName() + "." + n);
	}

	public Path file() {
		return file;
	}
}
