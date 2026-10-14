package dev.luminary.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.npc.autonomy.AutonomyRules;
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
 *            "requestTimeoutSeconds": 20, "dialogueTimeoutSeconds": 8, "maxConcurrent": 2, "allowedHosts": [], "logContent": false } }
 * dialogueProvider: scripted (default) | disabled | model (needs llm.provider other than none; falls back to scripted)
 * intentPlanner: keyword (default) | none | model (falls back to keyword)
 * autonomy: { "enabled": false, "thinkIntervalTicks": 100, "maxDecisionsPerHour": 12, "failureCooldownSeconds": 600 }
 *   (server-wide switch, off by default; each NPC additionally needs /luminary npc autonomy <npc> on)
 * conversation: { "containerGrantPermission": 0, "thinkingLine": true, "requireName": true, "chatRange": 0 }
 *   (operator level needed to hand the NPC a chest by chat, 0 = anyone; show "…" at once while a model answers;
 *    requireName: the NPC hears only lines containing one of its aliases, at any distance; chatRange applies only
 *    when requireName is false, 0 = unlimited)
 * llm.keepAliveSeconds (default 3600) keeps the Ollama model loaded between turns; llm.contextTokens (default 4096)
 * </pre>
 */
public record LuminaryConfig(String dialogueProvider, String intentPlanner, LlmConfig llm, boolean llmLogContent, Duration dialogueTimeout,
							 boolean autonomyEnabled, AutonomyRules autonomy, int containerGrantPermission, boolean thinkingLine,
							 boolean requireName, double chatRange) {
	public static final LuminaryConfig DEFAULTS = new LuminaryConfig("scripted", "keyword", LlmConfig.DISABLED, false, Duration.ofSeconds(8), false, AutonomyRules.DEFAULTS, 0, true, true, 0);

	public LuminaryConfig(String dialogueProvider, String intentPlanner, LlmConfig llm, boolean llmLogContent, Duration dialogueTimeout,
						  boolean autonomyEnabled, AutonomyRules autonomy, int containerGrantPermission, boolean thinkingLine) {
		this(dialogueProvider, intentPlanner, llm, llmLogContent, dialogueTimeout, autonomyEnabled, autonomy, containerGrantPermission, thinkingLine, true, 0);
	}
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static LuminaryConfig load(Path file) {
		return load(file, System.getenv(LlmConfig.ENV_API_KEY));
	}

	/** @param apiKeyFromEnv the value of {@code LUMINARY_LLM_API_KEY}, or null. Never logged. */
	public static LuminaryConfig load(Path file, String apiKeyFromEnv) {
		Optional<String> key = Optional.ofNullable(apiKeyFromEnv).map(String::trim).filter(s -> !s.isEmpty());
		if (!Files.exists(file)) {
			write(file, DEFAULTS);
			return new LuminaryConfig(DEFAULTS.dialogueProvider(), DEFAULTS.intentPlanner(), DEFAULTS.llm().withApiKey(key), false, DEFAULTS.dialogueTimeout(), false, AutonomyRules.DEFAULTS, 0, true);
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			String provider = o.has("dialogueProvider") ? o.get("dialogueProvider").getAsString() : DEFAULTS.dialogueProvider();
			String planner = o.has("intentPlanner") ? o.get("intentPlanner").getAsString() : DEFAULTS.intentPlanner();
			LlmConfig llm = LlmConfig.DISABLED;
			boolean logContent = false;
			Duration dialogueTimeout = DEFAULTS.dialogueTimeout();
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
						Duration.ofSeconds(l.has("keepAliveSeconds") ? l.get("keepAliveSeconds").getAsLong() : 3600),
						l.has("contextTokens") ? l.get("contextTokens").getAsInt() : LlmConfig.DISABLED.contextTokens());
				logContent = l.has("logContent") && l.get("logContent").getAsBoolean();
				if (l.has("dialogueTimeoutSeconds")) {
					dialogueTimeout = Duration.ofSeconds(l.get("dialogueTimeoutSeconds").getAsLong());
				}
			}
			boolean autonomyEnabled = false;
			AutonomyRules autonomy = AutonomyRules.DEFAULTS;
			if (o.has("autonomy") && o.get("autonomy").isJsonObject()) {
				JsonObject a = o.getAsJsonObject("autonomy");
				autonomyEnabled = a.has("enabled") && a.get("enabled").getAsBoolean();
				autonomy = new AutonomyRules(
						a.has("thinkIntervalTicks") ? a.get("thinkIntervalTicks").getAsInt() : AutonomyRules.DEFAULTS.thinkIntervalTicks(),
						a.has("maxDecisionsPerHour") ? a.get("maxDecisionsPerHour").getAsInt() : AutonomyRules.DEFAULTS.maxDecisionsPerWindow(),
						Duration.ofHours(1),
						Duration.ofSeconds(a.has("failureCooldownSeconds") ? a.get("failureCooldownSeconds").getAsLong() : AutonomyRules.DEFAULTS.failureCooldown().toSeconds()));
			}
			int grant = 0;
			boolean thinking = true;
			boolean requireName = true;
			double chatRange = 0;
			if (o.has("conversation") && o.get("conversation").isJsonObject()) {
				JsonObject c = o.getAsJsonObject("conversation");
				grant = c.has("containerGrantPermission") ? c.get("containerGrantPermission").getAsInt() : 0;
				thinking = !c.has("thinkingLine") || c.get("thinkingLine").getAsBoolean();
				requireName = !c.has("requireName") || c.get("requireName").getAsBoolean();
				chatRange = c.has("chatRange") ? c.get("chatRange").getAsDouble() : 0;
			}
			return new LuminaryConfig(provider, planner, llm.withApiKey(key), logContent, dialogueTimeout, autonomyEnabled, autonomy, grant, thinking, requireName, chatRange);
		} catch (IOException | RuntimeException e) {
			Luminary.LOGGER.error("[luminary] cannot read {}; using defaults ({})", file, e.getMessage());
			return new LuminaryConfig(DEFAULTS.dialogueProvider(), DEFAULTS.intentPlanner(), DEFAULTS.llm().withApiKey(key), false, DEFAULTS.dialogueTimeout(), false, AutonomyRules.DEFAULTS, 0, true);
		}
	}

	private static void write(Path file, LuminaryConfig config) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			JsonObject o = new JsonObject();
			o.addProperty("dialogueProvider", config.dialogueProvider());
			o.addProperty("intentPlanner", config.intentPlanner());
			JsonObject llm = new JsonObject();
			llm.addProperty("provider", config.llm().provider());
			llm.addProperty("endpoint", config.llm().endpoint().toString());
			llm.addProperty("model", config.llm().model());
			llm.addProperty("requestTimeoutSeconds", config.llm().requestTimeout().toSeconds());
			llm.addProperty("dialogueTimeoutSeconds", config.dialogueTimeout().toSeconds());
			llm.addProperty("maxConcurrent", config.llm().maxConcurrent());
			llm.addProperty("keepAliveSeconds", config.llm().keepAlive().toSeconds());
			llm.addProperty("contextTokens", config.llm().contextTokens());
			llm.add("allowedHosts", new JsonArray());
			llm.addProperty("logContent", config.llmLogContent());
			o.add("llm", llm);
			JsonObject autonomy = new JsonObject();
			autonomy.addProperty("enabled", config.autonomyEnabled());
			autonomy.addProperty("thinkIntervalTicks", config.autonomy().thinkIntervalTicks());
			autonomy.addProperty("maxDecisionsPerHour", config.autonomy().maxDecisionsPerWindow());
			autonomy.addProperty("failureCooldownSeconds", config.autonomy().failureCooldown().toSeconds());
			o.add("autonomy", autonomy);
			JsonObject conversation = new JsonObject();
			conversation.addProperty("containerGrantPermission", config.containerGrantPermission());
			conversation.addProperty("thinkingLine", config.thinkingLine());
			conversation.addProperty("requireName", config.requireName());
			conversation.addProperty("chatRange", config.chatRange());
			o.add("conversation", conversation);
			Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Luminary.LOGGER.warn("[luminary] cannot write default config {}: {}", file, e.getMessage());
		}
	}
}
