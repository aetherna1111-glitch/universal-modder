package dev.luminary.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.npc.llm.LlmConfig;
import dev.luminary.fabric.Luminary;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code config/luminary.json}. Only non-secret settings live here; the model API key is read from the environment
 * variable {@code LUMINARY_LLM_API_KEY} and a key found in the file is ignored with a warning. Missing file →
 * defaults are written (temp file + atomic rename); invalid JSON → defaults are used and the file is left untouched.
 * <pre>
 * { "dialogueProvider": "scripted",
 *   "llm": { "provider": "none", "endpoint": "http://127.0.0.1:11434", "model": "qwen2.5:7b",
 *            "requestTimeoutSeconds": 20, "maxConcurrent": 2, "allowedHosts": [], "logContent": false } }
 * </pre>
 */
public record LuminaryConfig(String dialogueProvider, LlmConfig llm, boolean llmLogContent) {
	public static final LuminaryConfig DEFAULTS = new LuminaryConfig("scripted", LlmConfig.DISABLED, false);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static LuminaryConfig load(Path file) {
		return load(file, System.getenv(LlmConfig.ENV_API_KEY));
	}

	/** @param apiKeyFromEnv the value of {@code LUMINARY_LLM_API_KEY}, or null. Never logged. */
	public static LuminaryConfig load(Path file, String apiKeyFromEnv) {
		Optional<String> key = Optional.ofNullable(apiKeyFromEnv).map(String::trim).filter(s -> !s.isEmpty());
		if (!Files.exists(file)) {
			write(file, DEFAULTS);
			return new LuminaryConfig(DEFAULTS.dialogueProvider(), DEFAULTS.llm().withApiKey(key), false);
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			String provider = o.has("dialogueProvider") ? o.get("dialogueProvider").getAsString() : DEFAULTS.dialogueProvider();
			LlmConfig llm = LlmConfig.DISABLED;
			boolean logContent = false;
			if (o.has("llm") && o.get("llm").isJsonObject()) {
				JsonObject l = o.getAsJsonObject("llm");
				if (l.has("apiKey")) {
					Luminary.LOGGER.warn("[luminary] config llm.apiKey is ignored: put the key in the environment variable {}", LlmConfig.ENV_API_KEY);
				}
				Set<String> hosts = new HashSet<>();
				if (l.has("allowedHosts")) {
					for (var h : l.getAsJsonArray("allowedHosts")) {
						hosts.add(h.getAsString().toLowerCase());
					}
				}
				llm = new LlmConfig(
						l.has("provider") ? l.get("provider").getAsString() : LlmConfig.PROVIDER_NONE,
						URI.create(l.has("endpoint") ? l.get("endpoint").getAsString() : LlmConfig.DISABLED.endpoint().toString()),
						l.has("model") ? l.get("model").getAsString() : LlmConfig.DISABLED.model(),
						Duration.ofSeconds(l.has("connectTimeoutSeconds") ? l.get("connectTimeoutSeconds").getAsLong() : 2),
						Duration.ofSeconds(l.has("requestTimeoutSeconds") ? l.get("requestTimeoutSeconds").getAsLong() : 20),
						l.has("maxResponseBytes") ? l.get("maxResponseBytes").getAsInt() : LlmConfig.DISABLED.maxResponseBytes(),
						l.has("maxConcurrent") ? l.get("maxConcurrent").getAsInt() : LlmConfig.DISABLED.maxConcurrent(),
						hosts, Optional.empty(),
						Duration.ofSeconds(l.has("keepAliveSeconds") ? l.get("keepAliveSeconds").getAsLong() : 300));
				logContent = l.has("logContent") && l.get("logContent").getAsBoolean();
			}
			return new LuminaryConfig(provider, llm.withApiKey(key), logContent);
		} catch (IOException | RuntimeException e) {
			Luminary.LOGGER.error("[luminary] cannot read {}; using defaults ({})", file, e.getMessage());
			return new LuminaryConfig(DEFAULTS.dialogueProvider(), DEFAULTS.llm().withApiKey(key), false);
		}
	}

	private static void write(Path file, LuminaryConfig config) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			JsonObject o = new JsonObject();
			o.addProperty("dialogueProvider", config.dialogueProvider());
			JsonObject llm = new JsonObject();
			llm.addProperty("provider", config.llm().provider());
			llm.addProperty("endpoint", config.llm().endpoint().toString());
			llm.addProperty("model", config.llm().model());
			llm.addProperty("requestTimeoutSeconds", config.llm().requestTimeout().toSeconds());
			llm.addProperty("maxConcurrent", config.llm().maxConcurrent());
			llm.add("allowedHosts", new JsonArray());
			llm.addProperty("logContent", config.llmLogContent());
			o.add("llm", llm);
			Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Luminary.LOGGER.warn("[luminary] cannot write default config {}: {}", file, e.getMessage());
		}
	}
}
