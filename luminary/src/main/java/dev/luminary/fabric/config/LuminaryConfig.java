package dev.luminary.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.fabric.Luminary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code config/luminary.json}. Only non-secret, non-gameplay settings live here. Missing file → defaults are
 * written (temp file + atomic rename); invalid JSON → defaults are used and the file is left untouched.
 * <pre>{ "dialogueProvider": "scripted" }   // or "disabled"</pre>
 */
public record LuminaryConfig(String dialogueProvider) {
	public static final LuminaryConfig DEFAULTS = new LuminaryConfig("scripted");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static LuminaryConfig load(Path file) {
		if (!Files.exists(file)) {
			write(file, DEFAULTS);
			return DEFAULTS;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			String provider = o.has("dialogueProvider") ? o.get("dialogueProvider").getAsString() : DEFAULTS.dialogueProvider();
			return new LuminaryConfig(provider);
		} catch (IOException | RuntimeException e) {
			Luminary.LOGGER.error("[luminary] cannot read {}; using defaults ({})", file, e.getMessage());
			return DEFAULTS;
		}
	}

	private static void write(Path file, LuminaryConfig config) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			JsonObject o = new JsonObject();
			o.addProperty("dialogueProvider", config.dialogueProvider());
			Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Luminary.LOGGER.warn("[luminary] cannot write default config {}: {}", file, e.getMessage());
		}
	}
}
