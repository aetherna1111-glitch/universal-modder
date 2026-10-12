package dev.luminary.fabric.definitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.catalog.DiscoveryTemplate;
import dev.luminary.core.catalog.Templates;
import dev.luminary.core.catalog.VerificationRequirement;
import dev.luminary.core.catalog.Visibility;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.Era;
import dev.luminary.core.progression.Prerequisite;
import dev.luminary.core.progression.ProgressionDefinitions;
import dev.luminary.core.progression.Technology;
import dev.luminary.fabric.Luminary;
import dev.luminary.fabric.LuminaryRuntime;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Loads Luminary definitions from every data pack:
 * <pre>
 * data/&lt;ns&gt;/luminary/discoveries/*.json    → DiscoveryTemplate
 * data/&lt;ns&gt;/luminary/eras/*.json           → Era
 * data/&lt;ns&gt;/luminary/technologies/*.json   → Technology
 * </pre>
 * A file with an error is reported with its path and skipped. If cross-references do not validate, the whole new set
 * is rejected and the previous definitions stay in force, so a bad reload never empties the game.
 */
public final class DefinitionsReloadListener implements SimpleSynchronousResourceReloadListener {
	private static final Identifier FABRIC_ID = new Identifier(Luminary.MOD_ID, "definitions");

	private static volatile Definitions current = Definitions.empty();

	public static Definitions current() {
		return current;
	}

	@Override
	public Identifier getFabricId() {
		return FABRIC_ID;
	}

	@Override
	public void reload(ResourceManager manager) {
		Templates templates = new Templates();
		ProgressionDefinitions progression = new ProgressionDefinitions();
		int[] errors = {0};
		load(manager, "luminary/discoveries", errors, (json, file) -> templates.add(parseTemplate(json, file)));
		load(manager, "luminary/eras", errors, (json, file) -> progression.addEra(parseEra(json, file)));
		load(manager, "luminary/technologies", errors, (json, file) -> progression.addTechnology(parseTechnology(json, file)));
		try {
			templates.validate();
			progression.validate(templates.all().stream().map(DiscoveryTemplate::id).collect(Collectors.toSet()));
		} catch (IllegalStateException e) {
			Luminary.LOGGER.error("[luminary] definitions rejected, keeping previous set: {}", e.getMessage());
			return;
		}
		current = new Definitions(templates, progression);
		LuminaryRuntime.get().ifPresent(rt -> rt.rebuildServices(current));
		Luminary.LOGGER.info("[luminary] loaded {} discovery templates, {} eras, {} technologies ({} files with errors)",
				templates.size(), progression.erasInOrder().size(), progression.technologies().size(), errors[0]);
	}

	private static void load(ResourceManager manager, String folder, int[] errors, BiConsumer<JsonObject, Identifier> sink) {
		for (Map.Entry<Identifier, Resource> entry : manager.findResources(folder, id -> id.getPath().endsWith(".json")).entrySet()) {
			Identifier file = entry.getKey();
			try (Reader reader = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
				sink.accept(JsonParser.parseReader(reader).getAsJsonObject(), file);
			} catch (IOException | RuntimeException e) {
				errors[0]++;
				Luminary.LOGGER.error("[luminary] bad definition {}: {}", file, e.getMessage());
			}
		}
	}

	// ------------------------------------------------------------------ parsers (package-private for tests)

	static DiscoveryTemplate parseTemplate(JsonObject json, Identifier file) {
		LuminaryId id = LuminaryId.parse(require(json, "id", file).getAsString());
		String titleKey = json.has("title") ? json.get("title").getAsString() : "discovery." + id.namespace() + "." + id.path().replace('/', '.');
		VerificationRequirement verification = VerificationRequirement.NONE;
		if (json.has("verification")) {
			JsonObject v = json.getAsJsonObject("verification");
			verification = new VerificationRequirement(
					v.has("minContributors") ? v.get("minContributors").getAsInt() : 1,
					v.has("minSamples") ? v.get("minSamples").getAsInt() : 0,
					ids(v, "experiments"),
					ids(v, "requiresVerified"));
		}
		Visibility visibility = json.has("visibility") ? Visibility.valueOf(json.get("visibility").getAsString().toUpperCase()) : Visibility.PUBLIC_WHEN_VERIFIED;
		return new DiscoveryTemplate(id, titleKey, verification, ids(json, "dependsOn"), visibility);
	}

	static Era parseEra(JsonObject json, Identifier file) {
		LuminaryId id = LuminaryId.parse(require(json, "id", file).getAsString());
		int order = require(json, "order", file).getAsInt();
		String titleKey = json.has("title") ? json.get("title").getAsString() : "era." + id.namespace() + "." + id.path().replace('/', '.');
		return new Era(id, order, titleKey, prerequisites(json, file));
	}

	static Technology parseTechnology(JsonObject json, Identifier file) {
		LuminaryId id = LuminaryId.parse(require(json, "id", file).getAsString());
		LuminaryId era = LuminaryId.parse(require(json, "era", file).getAsString());
		String titleKey = json.has("title") ? json.get("title").getAsString() : "technology." + id.namespace() + "." + id.path().replace('/', '.');
		return new Technology(id, era, titleKey, prerequisites(json, file), ids(json, "applies"), ids(json, "grants"));
	}

	/**
	 * {@code "prerequisites": [{"type":"era_at_least","era":"..."}, {"type":"discovery_verified","discovery":"..."},
	 * {"type":"technology_unlocked","technology":"..."}, {"type":"signal","signal":"...","min":1}]}
	 */
	static Set<Prerequisite> prerequisites(JsonObject json, Identifier file) {
		Set<Prerequisite> out = new HashSet<>();
		if (!json.has("prerequisites")) {
			return out;
		}
		for (JsonElement el : json.getAsJsonArray("prerequisites")) {
			JsonObject p = el.getAsJsonObject();
			String type = require(p, "type", file).getAsString();
			switch (type) {
				case "era_at_least" -> out.add(new Prerequisite.EraAtLeast(LuminaryId.parse(require(p, "era", file).getAsString())));
				case "discovery_verified" -> out.add(new Prerequisite.DiscoveryVerified(LuminaryId.parse(require(p, "discovery", file).getAsString())));
				case "technology_unlocked" -> out.add(new Prerequisite.TechnologyUnlocked(LuminaryId.parse(require(p, "technology", file).getAsString())));
				case "signal" -> out.add(new Prerequisite.Signal(LuminaryId.parse(require(p, "signal", file).getAsString()),
						p.has("min") ? p.get("min").getAsLong() : 1L));
				default -> throw new IllegalArgumentException("unknown prerequisite type '" + type + "' in " + file);
			}
		}
		return out;
	}

	private static JsonElement require(JsonObject o, String key, Identifier file) {
		if (!o.has(key)) {
			throw new IllegalArgumentException("missing '" + key + "' in " + file);
		}
		return o.get(key);
	}

	private static Set<LuminaryId> ids(JsonObject o, String key) {
		Set<LuminaryId> out = new HashSet<>();
		if (o.has(key)) {
			JsonArray arr = o.getAsJsonArray(key);
			for (JsonElement el : arr) {
				out.add(LuminaryId.parse(el.getAsString()));
			}
		}
		return out;
	}
}
