package dev.luminary.core.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.catalog.DiscoveryRecord;
import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.catalog.DiscoveryTemplate;
import dev.luminary.core.catalog.Evidence;
import dev.luminary.core.catalog.Templates;
import dev.luminary.core.catalog.VerificationRequirement;
import dev.luminary.core.catalog.Visibility;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivilizationStateCodecTest {
	static final LuminaryId ORE = LuminaryId.parse("luminary:material/first_trace_ore");
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

	private CivilizationState populated() {
		CivilizationState s = new CivilizationState();
		Templates t = new Templates().add(new DiscoveryTemplate(ORE, "t", VerificationRequirement.NONE, Set.of(), Visibility.PUBLIC_WHEN_VERIFIED));
		CatalogService svc = new CatalogService(s.catalog(), t, new EventBus((e, l, x) -> {}), Clock.fixed(NOW, ZoneOffset.UTC), () -> {});
		UUID alice = UUID.nameUUIDFromBytes("alice".getBytes());
		UUID bob = UUID.nameUUIDFromBytes("bob".getBytes());
		svc.observe(ORE, new Attribution(alice, "Alice", NOW, "minecraft:overworld", 12345L));
		svc.addEvidence(ORE, new Evidence.Sample(LuminaryId.of("s1"), bob, NOW, LuminaryId.parse("minecraft:raw_iron"), 3));
		svc.addEvidence(ORE, new Evidence.Experiment(LuminaryId.of("x1"), bob, NOW, LuminaryId.of("experiment/compass_drift"), Map.of("drift", "12deg")));
		svc.addEvidence(ORE, new Evidence.External(LuminaryId.of("e1"), bob, NOW, "thaumcraft", "research RESEARCH completed"));
		svc.addEvidence(ORE, new Evidence.Observation(LuminaryId.of("o1"), alice, NOW, "seen again"));
		svc.tryVerify(ORE, alice, false);
		s.claimEffect("catalog:verified:" + ORE + ":reward");
		return s;
	}

	@Test
	void roundTripPreservesEverything() {
		CivilizationStateCodec codec = CivilizationStateCodec.production();
		CivilizationState original = populated();
		String json = codec.encode(original);
		CivilizationState copy = codec.decode(json);

		assertEquals(codec.encode(original), codec.encode(copy), "re-encoding the decoded state must be byte-identical");
		DiscoveryRecord r = copy.catalog().get(ORE).orElseThrow();
		assertEquals(DiscoveryStatus.VERIFIED, r.status());
		assertEquals("Alice", r.firstObserver().playerNameAtTime());
		assertEquals(12345L, r.firstObserver().blockPosPacked());
		assertEquals(4, r.evidence().size());
		assertEquals(2, r.history().size());
		assertTrue(copy.executedEffectIds().contains("catalog:verified:" + ORE + ":reward"));
		assertFalse(copy.claimEffect("catalog:verified:" + ORE + ":reward"), "claimed effects stay claimed after reload");
	}

	@Test
	void schemaVersionIsWrittenAndNewerVersionsAreRefused() {
		CivilizationStateCodec codec = CivilizationStateCodec.production();
		JsonObject root = JsonParser.parseString(codec.encode(new CivilizationState())).getAsJsonObject();
		assertEquals(CivilizationState.CURRENT_SCHEMA_VERSION, root.get("schemaVersion").getAsInt());

		root.addProperty("schemaVersion", CivilizationState.CURRENT_SCHEMA_VERSION + 1);
		assertThrows(SchemaMigrations.UnsupportedSchemaException.class, () -> codec.decode(root.toString()));
	}

	@Test
	void migrationStepsRunInOrderAndStampVersions() {
		SchemaMigrations chain = new SchemaMigrations(1)
				.step(doc -> { doc.addProperty("a", 1); return doc; })      // 1 -> 2
				.step(doc -> { doc.addProperty("b", (doc.has("a") ? doc.get("a").getAsInt() : 0) + 1); return doc; });  // 2 -> 3
		JsonObject v1 = JsonParser.parseString("{\"schemaVersion\":1}").getAsJsonObject();
		JsonObject out = chain.migrate(v1);
		assertEquals(3, out.get("schemaVersion").getAsInt());
		assertEquals(2, out.get("b").getAsInt());
		assertFalse(v1.has("a"), "migrate must not mutate its input");

		JsonObject v2 = JsonParser.parseString("{\"schemaVersion\":2}").getAsJsonObject();
		JsonObject out2 = chain.migrate(v2);
		assertFalse(out2.has("a"), "a v2 document skips the 1->2 step");
		assertThrows(SchemaMigrations.UnsupportedSchemaException.class, () -> chain.migrate(JsonParser.parseString("{\"schemaVersion\":4}").getAsJsonObject()));
	}

	@Test
	void progressionRoundTripsAndV1DocumentsMigrate() {
		CivilizationStateCodec codec = CivilizationStateCodec.production();
		CivilizationState s = new CivilizationState();
		dev.luminary.core.progression.ProgressionState.Restore.era(s.progression(), LuminaryId.of("era/zero"), NOW);
		dev.luminary.core.progression.ProgressionState.Restore.currentEra(s.progression(), LuminaryId.of("era/zero"));
		dev.luminary.core.progression.ProgressionState.Restore.unlock(s.progression(), LuminaryId.of("tech/a"),
				new dev.luminary.core.progression.ProgressionState.Unlock(NOW, "admin_override:1_unmet", UUID.nameUUIDFromBytes("admin".getBytes())));
		dev.luminary.core.progression.ProgressionState.Restore.signal(s.progression(), LuminaryId.of("signal/x"), 42L);
		CivilizationState copy = codec.decode(codec.encode(s));
		assertEquals(LuminaryId.of("era/zero"), copy.progression().currentEra().orElseThrow());
		assertEquals("admin_override:1_unmet", copy.progression().unlocked().get(LuminaryId.of("tech/a")).reason());
		assertEquals(42L, copy.progression().signal(LuminaryId.of("signal/x")));
		assertEquals(codec.encode(s), codec.encode(copy));

		// A document written by the first slice (schema 1: catalog + executedEffects only) must load.
		String v1 = "{\"schemaVersion\":1,\"catalog\":{\"records\":[]},\"executedEffects\":[\"x\"]}";
		CivilizationState migrated = codec.decode(v1);
		assertTrue(migrated.progression().currentEra().isEmpty());
		assertTrue(migrated.progression().unlocked().isEmpty());
		assertFalse(migrated.claimEffect("x"));
		assertTrue(CivilizationState.CURRENT_SCHEMA_VERSION >= 2);
	}

	@Test
	void storyRoundTripsAndV2DocumentsMigrate() {
		CivilizationStateCodec codec = CivilizationStateCodec.production();
		CivilizationState s = new CivilizationState();
		UUID admin = UUID.nameUUIDFromBytes("admin".getBytes());
		dev.luminary.core.story.StoryState.Restore.phaseEntered(s.story(), LuminaryId.of("story/zero"), NOW);
		dev.luminary.core.story.StoryState.Restore.phaseEntered(s.story(), LuminaryId.of("story/one"), NOW.plusSeconds(5));
		dev.luminary.core.story.StoryState.Restore.currentPhase(s.story(), LuminaryId.of("story/one"));
		dev.luminary.core.story.StoryState.Restore.branch(s.story(), LuminaryId.of("story/branch/containment"));
		dev.luminary.core.story.StoryState.Restore.revealed(s.story(), LuminaryId.of("clue/hum"), NOW);
		dev.luminary.core.story.StoryState.Restore.history(s.story(), new dev.luminary.core.story.StoryState.PhaseChange(null, LuminaryId.of("story/zero"), NOW, "first_phase", null));
		dev.luminary.core.story.StoryState.Restore.history(s.story(), new dev.luminary.core.story.StoryState.PhaseChange(LuminaryId.of("story/zero"), LuminaryId.of("story/one"), NOW.plusSeconds(5), "admin", admin));
		CivilizationState copy = codec.decode(codec.encode(s));
		assertEquals(LuminaryId.of("story/one"), copy.story().currentPhase().orElseThrow());
		assertEquals(LuminaryId.of("story/branch/containment"), copy.story().branch().orElseThrow());
		assertTrue(copy.story().isRevealed(LuminaryId.of("clue/hum")));
		assertEquals(2, copy.story().history().size());
		assertEquals(admin, copy.story().history().get(1).actor());
		assertEquals(codec.encode(s), codec.encode(copy));

		String v2 = "{\"schemaVersion\":2,\"catalog\":{\"records\":[]},\"progression\":{\"eras\":{},\"unlocked\":{},\"signals\":{\"luminary:s\":3}},\"executedEffects\":[]}";
		CivilizationState migrated = codec.decode(v2);
		assertTrue(migrated.story().currentPhase().isEmpty());
		assertTrue(migrated.story().history().isEmpty());
		assertEquals(3L, migrated.progression().signal(LuminaryId.of("s")));
		assertEquals(3, CivilizationState.CURRENT_SCHEMA_VERSION);
	}

	@Test
	void emptyStateDecodesFromMinimalDocument() {
		CivilizationState s = CivilizationStateCodec.production().decode("{\"schemaVersion\":1}");
		assertEquals(0, s.catalog().size());
		assertTrue(s.executedEffectIds().isEmpty());
	}
}
