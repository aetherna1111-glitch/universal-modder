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

/**
 * Loads discovery templates from every data pack: {@code data/<ns>/luminary/discoveries/<name>.json}.
 * A file with an error is reported with its path and skipped; a dependency on an unknown template fails the whole
 * set and keeps the previous templates (so a bad reload never leaves the Catalog without definitions).
 */
public final class DefinitionsReloadListener implements SimpleSynchronousResourceReloadListener {
	private static final Identifier FABRIC_ID = new Identifier(Luminary.MOD_ID, "definitions");
	private static final String FOLDER = "luminary/discoveries";

	private static volatile Templates current = new Templates();

	public static Templates currentTemplates() {
		return current;
	}

	@Override
	public Identifier getFabricId() {
		return FABRIC_ID;
	}

	@Override
	public void reload(ResourceManager manager) {
		Templates loaded = new Templates();
		int errors = 0;
		for (Map.Entry<Identifier, Resource> entry : manager.findResources(FOLDER, id -> id.getPath().endsWith(".json")).entrySet()) {
			Identifier file = entry.getKey();
			try (Reader reader = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				loaded.add(parseTemplate(json, file));
			} catch (IOException | RuntimeException e) {
				errors++;
				Luminary.LOGGER.error("[luminary] bad discovery template {}: {}", file, e.getMessage());
			}
		}
		try {
			loaded.validate();
		} catch (IllegalStateException e) {
			Luminary.LOGGER.error("[luminary] discovery templates rejected, keeping previous set: {}", e.getMessage());
			return;
		}
		current = loaded;
		LuminaryRuntime.get().ifPresent(rt -> rt.rebuildServices(loaded));
		Luminary.LOGGER.info("[luminary] loaded {} discovery templates ({} files with errors)", loaded.size(), errors);
	}

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
