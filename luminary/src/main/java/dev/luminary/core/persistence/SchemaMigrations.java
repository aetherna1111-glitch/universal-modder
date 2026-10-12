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

	/** The production chain. Version 1 is the first persisted shape, so there are no steps yet. */
	public static SchemaMigrations production() {
		return new SchemaMigrations(CivilizationState.CURRENT_SCHEMA_VERSION);
	}
}
