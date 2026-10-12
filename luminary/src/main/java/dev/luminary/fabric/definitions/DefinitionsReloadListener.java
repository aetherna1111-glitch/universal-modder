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
import dev.luminary.core.story.Clue;
import dev.luminary.core.story.StoryBranch;
import dev.luminary.core.story.StoryCondition;
import dev.luminary.core.story.StoryDefinitions;
import dev.luminary.core.story.StoryPhase;
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
 * data/&lt;ns&gt;/luminary/story/phases/*.json   → StoryPhase
 * data/&lt;ns&gt;/luminary/story/clues/*.json    → Clue
 * data/&lt;ns&gt;/luminary/story/branches/*.json → StoryBranch
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
		StoryDefinitions story = new StoryDefinitions();
		int[] errors = {0};
		load(manager, "luminary/discoveries", errors, (json, file) -> templates.add(parseTemplate(json, file)));
		load(manager, "luminary/eras", errors, (json, file) -> progression.addEra(parseEra(json, file)));
		load(manager, "luminary/technologies", errors, (json, file) -> progression.addTechnology(parseTechnology(json, file)));
		load(manager, "luminary/story/branches", errors, (json, file) -> story.addBranch(parseBranch(json, file)));
		load(manager, "luminary/story/phases", errors, (json, file) -> story.addPhase(parsePhase(json, file)));
		load(manager, "luminary/story/clues", errors, (json, file) -> story.addClue(parseClue(json, file)));
		try {
			templates.validate();
			Set<LuminaryId> known = templates.all().stream().map(DiscoveryTemplate::id).collect(Collectors.toSet());
			progression.validate(known);
			story.validate();
			// story conditions may reference progression ids: check them with the same rules
			ProgressionDefinitions probe = new ProgressionDefinitions();
			progression.erasInOrder().forEach(probe::addEra);
			progression.technologies().forEach(probe::addTechnology);
			probe.addTechnology(new Technology(new LuminaryId(Luminary.MOD_ID, "internal/story_conditions_probe"),
					progression.firstEra().map(Era::id).orElseThrow(() -> new IllegalStateException("story definitions need at least one era")),
					"internal", new HashSet<>(story.progressionPrerequisites()), Set.of(), Set.of()));
			probe.validate(known);
		} catch (IllegalStateException | java.util.NoSuchElementException e) {
			Luminary.LOGGER.error("[luminary] definitions rejected, keeping previous set: {}", e.getMessage());
			return;
		}
		current = new Definitions(templates, progression, story);
		LuminaryRuntime.get().ifPresent(rt -> rt.rebuildServices(current));
		Luminary.LOGGER.info("[luminary] loaded {} discovery templates, {} eras, {} technologies, {} story phases, {} clues, {} branches ({} files with errors)",
				templates.size(), progression.erasInOrder().size(), progression.technologies().size(),
				story.phasesInOrder().size(), story.clues().size(), story.branches().size(), errors[0]);
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

	static StoryBranch parseBranch(JsonObject json, Identifier file) {
		LuminaryId id = LuminaryId.parse(require(json, "id", file).getAsString());
		String titleKey = json.has("title") ? json.get("title").getAsString() : "story.branch." + id.namespace() + "." + id.path().replace('/', '.');
		return new StoryBranch(id, titleKey);
	}

	static StoryPhase parsePhase(JsonObject json, Identifier file) {
		LuminaryId id = LuminaryId.parse(require(json, "id", file).getAsString());
		int order = require(json, "order", file).getAsInt();
		String titleKey = json.has("title") ? json.get("title").getAsString() : "story.phase." + id.namespace() + "." + id.path().replace('/', '.');
		boolean auto = json.has("autoAdvance") && json.get("autoAdvance").getAsBoolean();
		return new StoryPhase(id, order, titleKey, conditions(json, "conditions", file), auto);
	}

	static Clue parseClue(JsonObject json, Identifier file) {
		LuminaryId id = LuminaryId.parse(require(json, "id", file).getAsString());
		LuminaryId phase = LuminaryId.parse(require(json, "phase", file).getAsString());
		String textKey = json.has("text") ? json.get("text").getAsString() : "story.clue." + id.namespace() + "." + id.path().replace('/', '.');
		return new Clue(id, phase, textKey, conditions(json, "conditions", file));
	}

	/**
	 * Story conditions: every progression prerequisite type plus {@code branch_chosen}, {@code clue_revealed},
	 * {@code phase_at_least}.
	 */
	static Set<StoryCondition> conditions(JsonObject json, String key, Identifier file) {
		Set<StoryCondition> out = new HashSet<>();
		if (!json.has(key)) {
			return out;
		}
		for (JsonElement el : json.getAsJsonArray(key)) {
			JsonObject c = el.getAsJsonObject();
			String type = require(c, "type", file).getAsString();
			switch (type) {
				case "branch_chosen" -> out.add(new StoryCondition.BranchChosen(LuminaryId.parse(require(c, "branch", file).getAsString())));
				case "clue_revealed" -> out.add(new StoryCondition.ClueRevealed(LuminaryId.parse(require(c, "clue", file).getAsString())));
				case "phase_at_least" -> out.add(new StoryCondition.PhaseAtLeast(LuminaryId.parse(require(c, "phase", file).getAsString())));
				default -> {
					JsonObject wrapper = new JsonObject();
					JsonArray one = new JsonArray();
					one.add(c);
					wrapper.add("prerequisites", one);
					prerequisites(wrapper, file).forEach(p -> out.add(new StoryCondition.Progression(p)));
				}
			}
		}
		return out;
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
