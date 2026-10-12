package dev.luminary.core.catalog;

import dev.luminary.core.ids.LuminaryId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A piece of evidence attached to a discovery. {@link #id()} makes additions idempotent: the same evidence id is
 * stored once, however often it is submitted (crash recovery, duplicated packets).
 */
public sealed interface Evidence {
	LuminaryId id();
	UUID contributor();
	Instant at();

	/** A player saw it again (counts towards distinct contributors). */
	record Observation(LuminaryId id, UUID contributor, Instant at, String what) implements Evidence {}

	/** A physical sample was handed in (item id and count are inputs for verification requirements). */
	record Sample(LuminaryId id, UUID contributor, Instant at, LuminaryId itemId, int count) implements Evidence {}

	/** A defined experiment was run; {@code result} is free-form key/value output. */
	record Experiment(LuminaryId id, UUID contributor, Instant at, LuminaryId experimentId, Map<String, String> result) implements Evidence {}

	/** Evidence reported by another mod through the integration layer (e.g. a Thaumcraft research completed). */
	record External(LuminaryId id, UUID contributor, Instant at, String sourceModId, String detail) implements Evidence {}
}
