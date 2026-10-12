package dev.luminary.core.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.catalog.CatalogState;
import dev.luminary.core.catalog.CatalogStateAccess;
import dev.luminary.core.catalog.DiscoveryRecord;
import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.catalog.Evidence;
import dev.luminary.core.catalog.StatusChange;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.ProgressionState;
import dev.luminary.core.story.StoryState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Hand-written JSON codec (Gson tree API) for {@link CivilizationState}. Explicit field names keep the on-disk
 * format independent of Java field names and make migrations reviewable. Gson ships with Minecraft, so no extra
 * runtime dependency is needed; the core only needs it on the compile classpath.
 */
public final class CivilizationStateCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final SchemaMigrations migrations;

	public CivilizationStateCodec(SchemaMigrations migrations) {
		this.migrations = migrations;
	}

	public static CivilizationStateCodec production() {
		return new CivilizationStateCodec(SchemaMigrations.production());
	}

	// ---------------------------------------------------------------- encode

	public String encode(CivilizationState state) {
		return GSON.toJson(toJson(state));
	}

	public JsonObject toJson(CivilizationState state) {
		JsonObject root = new JsonObject();
		root.addProperty(SchemaMigrations.SCHEMA_VERSION, CivilizationState.CURRENT_SCHEMA_VERSION);
		JsonArray records = new JsonArray();
		for (DiscoveryRecord r : state.catalog().all()) {
			records.add(recordToJson(r));
		}
		JsonObject catalog = new JsonObject();
		catalog.add("records", records);
		root.add("catalog", catalog);
		root.add("progression", progressionToJson(state.progression()));
		root.add("story", storyToJson(state.story()));
		JsonArray effects = new JsonArray();
		for (String id : state.executedEffectIds()) {
			effects.add(id);
		}
		root.add("executedEffects", effects);
		return root;
	}

	private static JsonObject progressionToJson(ProgressionState p) {
		JsonObject o = new JsonObject();
		p.currentEra().ifPresent(e -> o.addProperty("currentEra", e.toString()));
		JsonObject eras = new JsonObject();
		p.eraEnteredAt().forEach((id, at) -> eras.addProperty(id.toString(), at.toString()));
		o.add("eras", eras);
		JsonObject unlocked = new JsonObject();
		p.unlocked().forEach((id, u) -> {
			JsonObject uo = new JsonObject();
			uo.addProperty("at", u.at().toString());
			uo.addProperty("reason", u.reason());
			if (u.actor() != null) {
				uo.addProperty("actor", u.actor().toString());
			}
			unlocked.add(id.toString(), uo);
		});
		o.add("unlocked", unlocked);
		JsonObject signals = new JsonObject();
		p.signals().forEach((id, v) -> signals.addProperty(id.toString(), v));
		o.add("signals", signals);
		return o;
	}

	private static JsonObject storyToJson(StoryState st) {
		JsonObject o = new JsonObject();
		st.currentPhase().ifPresent(p -> o.addProperty("currentPhase", p.toString()));
		st.branch().ifPresent(b -> o.addProperty("branch", b.toString()));
		JsonObject phases = new JsonObject();
		st.phaseEnteredAt().forEach((id, at) -> phases.addProperty(id.toString(), at.toString()));
		o.add("phases", phases);
		JsonObject revealed = new JsonObject();
		st.revealedClues().forEach((id, at) -> revealed.addProperty(id.toString(), at.toString()));
		o.add("revealed", revealed);
		JsonArray history = new JsonArray();
		for (StoryState.PhaseChange c : st.history()) {
			JsonObject h = new JsonObject();
			if (c.from() != null) {
				h.addProperty("from", c.from().toString());
			}
			h.addProperty("to", c.to().toString());
			h.addProperty("at", c.at().toString());
			h.addProperty("reason", c.reason());
			if (c.actor() != null) {
				h.addProperty("actor", c.actor().toString());
			}
			history.add(h);
		}
		o.add("history", history);
		return o;
	}

	private static JsonObject recordToJson(DiscoveryRecord r) {
		JsonObject o = new JsonObject();
		o.addProperty("id", r.id().toString());
		o.addProperty("status", r.status().name());
		o.add("firstObserver", attributionToJson(r.firstObserver()));
		JsonArray evidence = new JsonArray();
		for (Evidence e : r.evidence()) {
			evidence.add(evidenceToJson(e));
		}
		o.add("evidence", evidence);
		JsonArray history = new JsonArray();
		for (StatusChange c : r.history()) {
			JsonObject h = new JsonObject();
			if (c.from() != null) {
				h.addProperty("from", c.from().name());
			}
			h.addProperty("to", c.to().name());
			h.addProperty("at", c.at().toString());
			h.addProperty("reason", c.reason());
			if (c.actor() != null) {
				h.addProperty("actor", c.actor().toString());
			}
			history.add(h);
		}
		o.add("history", history);
		return o;
	}

	private static JsonObject attributionToJson(Attribution a) {
		JsonObject o = new JsonObject();
		o.addProperty("player", a.playerId().toString());
		o.addProperty("name", a.playerNameAtTime());
		o.addProperty("at", a.at().toString());
		o.addProperty("dimension", a.dimension());
		o.addProperty("pos", a.blockPosPacked());
		return o;
	}

	private static JsonObject evidenceToJson(Evidence e) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.id().toString());
		o.addProperty("contributor", e.contributor().toString());
		o.addProperty("at", e.at().toString());
		if (e instanceof Evidence.Observation x) {
			o.addProperty("type", "observation");
			o.addProperty("what", x.what());
		} else if (e instanceof Evidence.Sample x) {
			o.addProperty("type", "sample");
			o.addProperty("item", x.itemId().toString());
			o.addProperty("count", x.count());
		} else if (e instanceof Evidence.Experiment x) {
			o.addProperty("type", "experiment");
			o.addProperty("experiment", x.experimentId().toString());
			JsonObject result = new JsonObject();
			x.result().forEach(result::addProperty);
			o.add("result", result);
		} else if (e instanceof Evidence.External x) {
			o.addProperty("type", "external");
			o.addProperty("sourceMod", x.sourceModId());
			o.addProperty("detail", x.detail());
		} else {
			throw new IllegalStateException("unknown evidence type " + e.getClass());
		}
		return o;
	}

	// ---------------------------------------------------------------- decode

	/** Parses, migrates and decodes. Throws {@link SchemaMigrations.UnsupportedSchemaException} for newer data. */
	public CivilizationState decode(String json) {
		JsonElement parsed = JsonParser.parseString(json);
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("civilization state must be a JSON object");
		}
		return fromJson(migrations.migrate(parsed.getAsJsonObject()));
	}

	public CivilizationState fromJson(JsonObject root) {
		CatalogState catalog = new CatalogState();
		JsonObject catalogJson = root.has("catalog") ? root.getAsJsonObject("catalog") : new JsonObject();
		if (catalogJson.has("records")) {
			for (JsonElement el : catalogJson.getAsJsonArray("records")) {
				DiscoveryRecord r = recordFromJson(el.getAsJsonObject());
				catalogPut(catalog, r);
			}
		}
		ProgressionState progression = progressionFromJson(root.has("progression") ? root.getAsJsonObject("progression") : new JsonObject());
		TreeSet<String> effects = new TreeSet<>();
		if (root.has("executedEffects")) {
			for (JsonElement el : root.getAsJsonArray("executedEffects")) {
				effects.add(el.getAsString());
			}
		}
		StoryState story = storyFromJson(root.has("story") ? root.getAsJsonObject("story") : new JsonObject());
		return new CivilizationState(catalog, progression, story, effects);
	}

	private static StoryState storyFromJson(JsonObject o) {
		StoryState st = new StoryState();
		if (o.has("phases")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("phases").entrySet()) {
				StoryState.Restore.phaseEntered(st, LuminaryId.parse(e.getKey()), Instant.parse(e.getValue().getAsString()));
			}
		}
		if (o.has("currentPhase")) {
			StoryState.Restore.currentPhase(st, LuminaryId.parse(o.get("currentPhase").getAsString()));
		}
		if (o.has("branch")) {
			StoryState.Restore.branch(st, LuminaryId.parse(o.get("branch").getAsString()));
		}
		if (o.has("revealed")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("revealed").entrySet()) {
				StoryState.Restore.revealed(st, LuminaryId.parse(e.getKey()), Instant.parse(e.getValue().getAsString()));
			}
		}
		if (o.has("history")) {
			for (JsonElement el : o.getAsJsonArray("history")) {
				JsonObject h = el.getAsJsonObject();
				StoryState.Restore.history(st, new StoryState.PhaseChange(h.has("from") ? LuminaryId.parse(h.get("from").getAsString()) : null,
						LuminaryId.parse(h.get("to").getAsString()), Instant.parse(h.get("at").getAsString()), h.get("reason").getAsString(),
						h.has("actor") ? UUID.fromString(h.get("actor").getAsString()) : null));
			}
		}
		return st;
	}

	private static ProgressionState progressionFromJson(JsonObject o) {
		ProgressionState p = new ProgressionState();
		if (o.has("eras")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("eras").entrySet()) {
				ProgressionState.Restore.era(p, LuminaryId.parse(e.getKey()), Instant.parse(e.getValue().getAsString()));
			}
		}
		if (o.has("currentEra")) {
			ProgressionState.Restore.currentEra(p, LuminaryId.parse(o.get("currentEra").getAsString()));
		}
		if (o.has("unlocked")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("unlocked").entrySet()) {
				JsonObject u = e.getValue().getAsJsonObject();
				ProgressionState.Restore.unlock(p, LuminaryId.parse(e.getKey()), new ProgressionState.Unlock(Instant.parse(u.get("at").getAsString()),
						u.get("reason").getAsString(), u.has("actor") ? UUID.fromString(u.get("actor").getAsString()) : null));
			}
		}
		if (o.has("signals")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("signals").entrySet()) {
				ProgressionState.Restore.signal(p, LuminaryId.parse(e.getKey()), e.getValue().getAsLong());
			}
		}
		return p;
	}

	private static void catalogPut(CatalogState state, DiscoveryRecord r) {
		// CatalogState.put is package-private by design; the codec is the one other writer.
		CatalogStateAccess.put(state, r);
	}

	private static DiscoveryRecord recordFromJson(JsonObject o) {
		List<Evidence> evidence = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray("evidence")) {
			evidence.add(evidenceFromJson(el.getAsJsonObject()));
		}
		List<StatusChange> history = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray("history")) {
			JsonObject h = el.getAsJsonObject();
			history.add(new StatusChange(h.has("from") ? DiscoveryStatus.valueOf(h.get("from").getAsString()) : null,
					DiscoveryStatus.valueOf(h.get("to").getAsString()), Instant.parse(h.get("at").getAsString()),
					h.get("reason").getAsString(), h.has("actor") ? UUID.fromString(h.get("actor").getAsString()) : null));
		}
		return new DiscoveryRecord(LuminaryId.parse(o.get("id").getAsString()), DiscoveryStatus.valueOf(o.get("status").getAsString()),
				attributionFromJson(o.getAsJsonObject("firstObserver")), evidence, history);
	}

	private static Attribution attributionFromJson(JsonObject o) {
		return new Attribution(UUID.fromString(o.get("player").getAsString()), o.get("name").getAsString(),
				Instant.parse(o.get("at").getAsString()), o.get("dimension").getAsString(), o.get("pos").getAsLong());
	}

	private static Evidence evidenceFromJson(JsonObject o) {
		LuminaryId id = LuminaryId.parse(o.get("id").getAsString());
		UUID contributor = UUID.fromString(o.get("contributor").getAsString());
		Instant at = Instant.parse(o.get("at").getAsString());
		String type = o.get("type").getAsString();
		switch (type) {
			case "observation":
				return new Evidence.Observation(id, contributor, at, o.get("what").getAsString());
			case "sample":
				return new Evidence.Sample(id, contributor, at, LuminaryId.parse(o.get("item").getAsString()), o.get("count").getAsInt());
			case "experiment": {
				Map<String, String> result = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("result").entrySet()) {
					result.put(e.getKey(), e.getValue().getAsString());
				}
				return new Evidence.Experiment(id, contributor, at, LuminaryId.parse(o.get("experiment").getAsString()), result);
			}
			case "external":
				return new Evidence.External(id, contributor, at, o.get("sourceMod").getAsString(), o.get("detail").getAsString());
			default:
				throw new IllegalArgumentException("unknown evidence type '" + type + "'");
		}
	}
}
