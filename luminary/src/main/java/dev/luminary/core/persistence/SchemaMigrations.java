package dev.luminary.core.persistence;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Ordered JSON-tree migrations. Step {@code n} converts a document with {@code schemaVersion == n} to {@code n + 1}.
 * Loading refuses documents newer than {@link CivilizationState#CURRENT_SCHEMA_VERSION} (fail closed; never
 * overwrite data written by a newer Luminary).
 */
public final class SchemaMigrations {
	public static final String SCHEMA_VERSION = "schemaVersion";

	public static final class UnsupportedSchemaException extends RuntimeException {
		public UnsupportedSchemaException(String message) {
			super(message);
		}
	}

	private final List<UnaryOperator<JsonObject>> steps = new ArrayList<>();
	private final int firstVersion;

	/** @param firstVersion the schema version the first registered step upgrades from. */
	public SchemaMigrations(int firstVersion) {
		this.firstVersion = firstVersion;
	}

	public SchemaMigrations step(UnaryOperator<JsonObject> migration) {
		steps.add(migration);
		return this;
	}

	public int targetVersion() {
		return firstVersion + steps.size();
	}

	/** Applies the needed steps in order and stamps the final version. Pure: returns a new object. */
	public JsonObject migrate(JsonObject document) {
		int version = document.has(SCHEMA_VERSION) ? document.get(SCHEMA_VERSION).getAsInt() : firstVersion;
		int target = targetVersion();
		if (version > target) {
			throw new UnsupportedSchemaException("document schemaVersion " + version + " is newer than supported " + target);
		}
		if (version < firstVersion) {
			throw new UnsupportedSchemaException("document schemaVersion " + version + " predates the first known version " + firstVersion);
		}
		JsonObject current = document.deepCopy();
		for (int v = version; v < target; v++) {
			current = steps.get(v - firstVersion).apply(current);
			current.addProperty(SCHEMA_VERSION, v + 1);
		}
		current.addProperty(SCHEMA_VERSION, target);
		return current;
	}

	/**
	 * The production chain, starting at the first persisted shape (v1). Steps:
	 * <ol>
	 *   <li>v1 → v2: adds the {@code progression} object (no era, no unlocks, no signals).</li>
	 *   <li>v2 → v3: adds the {@code story} object (no phase, no branch, no revealed clues, empty history).</li>
	 * </ol>
	 */
	public static SchemaMigrations production() {
		return new SchemaMigrations(1)
				.step(doc -> {
					if (!doc.has("progression")) {
						JsonObject p = new JsonObject();
						p.add("eras", new JsonObject());
						p.add("unlocked", new JsonObject());
						p.add("signals", new JsonObject());
						doc.add("progression", p);
					}
					return doc;
				})
				.step(doc -> {
					if (!doc.has("story")) {
						JsonObject st = new JsonObject();
						st.add("phases", new JsonObject());
						st.add("revealed", new JsonObject());
						st.add("history", new com.google.gson.JsonArray());
						doc.add("story", st);
					}
					return doc;
				});
	}
}
