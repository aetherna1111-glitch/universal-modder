package dev.luminary.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.autonomy.AutonomyRules;
import dev.luminary.core.npc.combat.CombatRules;
import dev.luminary.core.npc.presence.GazeTracker;
import dev.luminary.core.npc.presence.MutterChannel;
import dev.luminary.core.npc.relationship.RelationshipRules;
import dev.luminary.core.npc.weapon.WeaponCustody;
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
							 boolean requireName, double chatRange, Behaviour behaviour) {
	/**
	 * Presence, self-defence, relationships, the unique weapon and the daily routine (slice 15). {@code language} picks
	 * the authored line set of the deterministic channels ({@code en} or {@code ru}).
	 * <pre>
	 * presence: { "language": "en", "gazeCooldownSeconds": 45, "gazeGlobalCooldownSeconds": 10, "gazeMinimumMillis": 600,
	 *             "whisperEnabled": true, "whisperRange": 5.0, "whisperCooldownSeconds": 90, "whisperJitterSeconds": 45 }
	 * combat:   { "attacksBeforeCombat": 3, "attackWindowSeconds": 60, "deescalateDistance": 8.0, "deescalateAfterSeconds": 20,
	 *             "recoverySeconds": 30, "weaponDamageFraction": 0.5, "strikeReach": 3.5, "holdGroundRadius": 6.0,
	 *             "knockoutMinutes": 5, "helpShortensBySeconds": 60, "duelConsentSeconds": 90, "shieldFirst": true }
	 * relationship: { "threatHalfLifeMinutes": 10, "karmaRecoveryPerDay": 2.0, "hostileThreat": 60, "waryThreat": 25, "friendKarma": 20, "friendTrust": 30 }
	 * weapon:   { "theftDurationMinutes": 60, "blessings": ["minecraft:sharpness:6", "mcdw:smiting:3", ...] }  (enchantments minted onto the sword; unknown ids skipped)
	 * routine:  { "enabled": true }
	 * </pre>
	 */
	public record Behaviour(String language, GazeTracker.Rules gaze, MutterChannel.Rules mutter, CombatRules combat, RelationshipRules relationship,
							Duration theftDuration, boolean routineEnabled, java.util.List<String> blessings) {
		public static final Behaviour DEFAULTS = new Behaviour("en", GazeTracker.Rules.DEFAULTS, MutterChannel.Rules.DEFAULTS, CombatRules.DEFAULTS,
				RelationshipRules.DEFAULTS, WeaponCustody.DEFAULT_THEFT_DURATION, true, dev.luminary.fabric.npc.HolySwordItem.DEFAULT_BLESSINGS);

		public Behaviour(String language, GazeTracker.Rules gaze, MutterChannel.Rules mutter, CombatRules combat, RelationshipRules relationship,
						 Duration theftDuration, boolean routineEnabled) {
			this(language, gaze, mutter, combat, relationship, theftDuration, routineEnabled, dev.luminary.fabric.npc.HolySwordItem.DEFAULT_BLESSINGS);
		}

		public Behaviour {
			blessings = java.util.List.copyOf(blessings);
		}

		public NpcRoster.Rules rosterRules() {
			return new NpcRoster.Rules(gaze, mutter, combat, relationship);
		}
	}

	public static final LuminaryConfig DEFAULTS = new LuminaryConfig("scripted", "keyword", LlmConfig.DISABLED, false, Duration.ofSeconds(8), false, AutonomyRules.DEFAULTS, 0, true, true, 0, Behaviour.DEFAULTS);

	public LuminaryConfig(String dialogueProvider, String intentPlanner, LlmConfig llm, boolean llmLogContent, Duration dialogueTimeout,
						  boolean autonomyEnabled, AutonomyRules autonomy, int containerGrantPermission, boolean thinkingLine,
						  boolean requireName, double chatRange) {
		this(dialogueProvider, intentPlanner, llm, llmLogContent, dialogueTimeout, autonomyEnabled, autonomy, containerGrantPermission, thinkingLine, requireName, chatRange, Behaviour.DEFAULTS);
	}

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
			return new LuminaryConfig(provider, planner, llm.withApiKey(key), logContent, dialogueTimeout, autonomyEnabled, autonomy, grant, thinking, requireName, chatRange, behaviour(o));
		} catch (IOException | RuntimeException e) {
			Luminary.LOGGER.error("[luminary] cannot read {}; using defaults ({})", file, e.getMessage());
			return new LuminaryConfig(DEFAULTS.dialogueProvider(), DEFAULTS.intentPlanner(), DEFAULTS.llm().withApiKey(key), false, DEFAULTS.dialogueTimeout(), false, AutonomyRules.DEFAULTS, 0, true);
		}
	}

	static Behaviour behaviour(JsonObject o) {
		Behaviour d = Behaviour.DEFAULTS;
		String language = d.language();
		GazeTracker.Rules gaze = d.gaze();
		MutterChannel.Rules mutter = d.mutter();
		if (o.has("presence") && o.get("presence").isJsonObject()) {
			JsonObject p = o.getAsJsonObject("presence");
			language = p.has("language") ? p.get("language").getAsString() : language;
			gaze = new GazeTracker.Rules(seconds(p, "gazeCooldownSeconds", d.gaze().perPlayer()), seconds(p, "gazeGlobalCooldownSeconds", d.gaze().global()),
					p.has("gazeMinimumMillis") ? Duration.ofMillis(p.get("gazeMinimumMillis").getAsLong()) : d.gaze().minimumGaze());
			mutter = new MutterChannel.Rules(p.has("whisperRange") ? p.get("whisperRange").getAsDouble() : d.mutter().range(),
					seconds(p, "whisperCooldownSeconds", d.mutter().cooldown()), seconds(p, "whisperJitterSeconds", d.mutter().jitter()),
					!p.has("whisperEnabled") || p.get("whisperEnabled").getAsBoolean());
		}
		CombatRules combat = d.combat();
		if (o.has("combat") && o.get("combat").isJsonObject()) {
			JsonObject c = o.getAsJsonObject("combat");
			CombatRules x = d.combat();
			combat = new CombatRules(c.has("attacksBeforeCombat") ? c.get("attacksBeforeCombat").getAsInt() : x.attacksBeforeCombat(),
					seconds(c, "attackWindowSeconds", x.attackWindow()),
					c.has("deescalateDistance") ? c.get("deescalateDistance").getAsDouble() : x.deescalateDistance(),
					seconds(c, "deescalateAfterSeconds", x.deescalateAfter()), seconds(c, "recoverySeconds", x.recovery()),
					c.has("weaponDamageFraction") ? c.get("weaponDamageFraction").getAsDouble() : x.weaponDamageFraction(),
					c.has("strikeReach") ? c.get("strikeReach").getAsDouble() : x.strikeReach(),
					c.has("holdGroundRadius") ? c.get("holdGroundRadius").getAsDouble() : x.holdGroundRadius(),
					c.has("knockoutMinutes") ? Duration.ofMinutes(c.get("knockoutMinutes").getAsLong()) : x.knockout(),
					seconds(c, "helpShortensBySeconds", x.helpShortensBy()), seconds(c, "duelConsentSeconds", x.duelConsentWindow()),
					!c.has("shieldFirst") || c.get("shieldFirst").getAsBoolean());
		}
		RelationshipRules relationship = d.relationship();
		if (o.has("relationship") && o.get("relationship").isJsonObject()) {
			JsonObject r = o.getAsJsonObject("relationship");
			RelationshipRules x = d.relationship();
			relationship = new RelationshipRules(x.deltas(),
					r.has("threatHalfLifeMinutes") ? Duration.ofMinutes(r.get("threatHalfLifeMinutes").getAsLong()) : x.threatHalfLife(),
					r.has("karmaRecoveryPerDay") ? r.get("karmaRecoveryPerDay").getAsDouble() : x.karmaRecoveryPerDay(),
					r.has("hostileThreat") ? r.get("hostileThreat").getAsInt() : x.hostileThreat(),
					r.has("waryThreat") ? r.get("waryThreat").getAsInt() : x.waryThreat(),
					r.has("friendKarma") ? r.get("friendKarma").getAsInt() : x.friendKarma(),
					r.has("friendTrust") ? r.get("friendTrust").getAsInt() : x.friendTrust());
		}
		Duration theft = d.theftDuration();
		java.util.List<String> blessings = d.blessings();
		if (o.has("weapon") && o.get("weapon").isJsonObject()) {
			JsonObject w = o.getAsJsonObject("weapon");
			theft = w.has("theftDurationMinutes") ? Duration.ofMinutes(w.get("theftDurationMinutes").getAsLong()) : theft;
			if (w.has("blessings") && w.get("blessings").isJsonArray()) {
				java.util.List<String> list = new java.util.ArrayList<>();
				for (var el : w.getAsJsonArray("blessings")) {
					list.add(el.getAsString());
				}
				blessings = list;
			}
		}
		boolean routine = d.routineEnabled();
		if (o.has("routine") && o.get("routine").isJsonObject()) {
			JsonObject r = o.getAsJsonObject("routine");
			routine = !r.has("enabled") || r.get("enabled").getAsBoolean();
		}
		return new Behaviour(language, gaze, mutter, combat, relationship, theft, routine, blessings);
	}

	private static Duration seconds(JsonObject o, String key, Duration fallback) {
		return o.has(key) ? Duration.ofSeconds(o.get(key).getAsLong()) : fallback;
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
			Behaviour b = config.behaviour();
			JsonObject presence = new JsonObject();
			presence.addProperty("language", b.language());
			presence.addProperty("gazeCooldownSeconds", b.gaze().perPlayer().toSeconds());
			presence.addProperty("gazeGlobalCooldownSeconds", b.gaze().global().toSeconds());
			presence.addProperty("gazeMinimumMillis", b.gaze().minimumGaze().toMillis());
			presence.addProperty("whisperEnabled", b.mutter().enabled());
			presence.addProperty("whisperRange", b.mutter().range());
			presence.addProperty("whisperCooldownSeconds", b.mutter().cooldown().toSeconds());
			presence.addProperty("whisperJitterSeconds", b.mutter().jitter().toSeconds());
			o.add("presence", presence);
			JsonObject combat = new JsonObject();
			combat.addProperty("attacksBeforeCombat", b.combat().attacksBeforeCombat());
			combat.addProperty("attackWindowSeconds", b.combat().attackWindow().toSeconds());
			combat.addProperty("deescalateDistance", b.combat().deescalateDistance());
			combat.addProperty("deescalateAfterSeconds", b.combat().deescalateAfter().toSeconds());
			combat.addProperty("recoverySeconds", b.combat().recovery().toSeconds());
			combat.addProperty("weaponDamageFraction", b.combat().weaponDamageFraction());
			combat.addProperty("strikeReach", b.combat().strikeReach());
			combat.addProperty("holdGroundRadius", b.combat().holdGroundRadius());
			combat.addProperty("knockoutMinutes", b.combat().knockout().toMinutes());
			combat.addProperty("helpShortensBySeconds", b.combat().helpShortensBy().toSeconds());
			combat.addProperty("duelConsentSeconds", b.combat().duelConsentWindow().toSeconds());
			combat.addProperty("shieldFirst", b.combat().shieldFirst());
			o.add("combat", combat);
			JsonObject relationship = new JsonObject();
			relationship.addProperty("threatHalfLifeMinutes", b.relationship().threatHalfLife().toMinutes());
			relationship.addProperty("karmaRecoveryPerDay", b.relationship().karmaRecoveryPerDay());
			relationship.addProperty("hostileThreat", b.relationship().hostileThreat());
			relationship.addProperty("waryThreat", b.relationship().waryThreat());
			relationship.addProperty("friendKarma", b.relationship().friendKarma());
			relationship.addProperty("friendTrust", b.relationship().friendTrust());
			o.add("relationship", relationship);
			JsonObject weapon = new JsonObject();
			weapon.addProperty("theftDurationMinutes", b.theftDuration().toMinutes());
			JsonArray blessings = new JsonArray();
			b.blessings().forEach(blessings::add);
			weapon.add("blessings", blessings);
			o.add("weapon", weapon);
			JsonObject routine = new JsonObject();
			routine.addProperty("enabled", b.routineEnabled());
			o.add("routine", routine);
			Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Luminary.LOGGER.warn("[luminary] cannot write default config {}: {}", file, e.getMessage());
		}
	}
}
