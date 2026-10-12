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
	void emptyStateDecodesFromMinimalDocument() {
		CivilizationState s = CivilizationStateCodec.production().decode("{\"schemaVersion\":1}");
		assertEquals(0, s.catalog().size());
		assertTrue(s.executedEffectIds().isEmpty());
	}
}
