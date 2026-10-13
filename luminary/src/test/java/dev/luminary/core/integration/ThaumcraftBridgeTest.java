package dev.luminary.core.integration;

import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThaumcraftBridgeTest {
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

	static Map<String, Object> event(String type, Object... kv) {
		Map<String, Object> m = new HashMap<>();
		m.put("type", type);
		m.put("player", ALICE);
		m.put("playerName", "Alice");
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	void researchBecomesTwoIncrementsAndAMemory() {
		ThaumcraftBridge.Observation o = ThaumcraftBridge.translate(event("research_completed", "key", "THAUMOMETER", "warp", 0)).orElseThrow();
		assertEquals(List.of(
				new ThaumcraftBridge.SignalDelta(LuminaryId.of("signal/thaumcraft/research/thaumometer"), ThaumcraftBridge.Mode.INCREMENT, 1),
				new ThaumcraftBridge.SignalDelta(LuminaryId.of("signal/thaumcraft/research_total"), ThaumcraftBridge.Mode.INCREMENT, 1)), o.signals());
		assertEquals(ALICE, o.memory().player());
		assertEquals("thaumcraft/research/thaumometer", o.memory().topic());
		assertTrue(o.memory().text().contains("Alice completed the Thaumcraft research THAUMOMETER"));
	}

	@Test
	void scanAndAspectEvents() {
		ThaumcraftBridge.Observation scan = ThaumcraftBridge.translate(event("scan_completed", "scanType", "item", "target", "minecraft:oak_log",
				"prefix", "@", "aspects", Map.of("arbor", 4))).orElseThrow();
		assertEquals(LuminaryId.of("signal/thaumcraft/scan/minecraft/oak_log"), scan.signals().get(0).signal());
		assertEquals(LuminaryId.of("signal/thaumcraft/scans"), scan.signals().get(1).signal());
		assertTrue(scan.memory().text().contains("scanned the item minecraft:oak_log and learned {arbor=4}"));
		ThaumcraftBridge.Observation aspect = ThaumcraftBridge.translate(event("aspect_discovered", "aspect", "Praecantatio")).orElseThrow();
		assertEquals(new ThaumcraftBridge.SignalDelta(LuminaryId.of("signal/thaumcraft/aspect/praecantatio"), ThaumcraftBridge.Mode.AT_LEAST, 1), aspect.signals().get(0));
	}

	@Test
	void playerMayArriveAsStringAndMalformedEventsAreIgnored() {
		Map<String, Object> e = event("research_completed", "key", "BASICS");
		e.put("player", ALICE.toString());
		assertEquals(ALICE, ThaumcraftBridge.translate(e).orElseThrow().memory().player());
		assertEquals(Optional.empty(), ThaumcraftBridge.translate(null));
		assertEquals(Optional.empty(), ThaumcraftBridge.translate(Map.of("type", "research_completed")), "no player");
		assertEquals(Optional.empty(), ThaumcraftBridge.translate(event("research_completed")), "no key");
		assertEquals(Optional.empty(), ThaumcraftBridge.translate(event("research_completed", "key", "   ")));
		assertEquals(Optional.empty(), ThaumcraftBridge.translate(event("warp_changed", "amount", 3)), "unknown type");
		Map<String, Object> badUuid = event("aspect_discovered", "aspect", "ignis");
		badUuid.put("player", "not-a-uuid");
		assertEquals(Optional.empty(), ThaumcraftBridge.translate(badUuid));
	}

	@Test
	void slugsAreValidIdPaths() {
		assertEquals("basics", ThaumcraftBridge.slug("BASICS"));
		assertEquals("node_tapper_1", ThaumcraftBridge.slug("NODE TAPPER (1)"));
		assertEquals("minecraft/oak_log", ThaumcraftBridge.slug("minecraft:oak_log"));
		assertEquals("unknown", ThaumcraftBridge.slug("!!!"));
		assertEquals(80, ThaumcraftBridge.slug("x".repeat(500)).length());
		LuminaryId.of("signal/thaumcraft/research/" + ThaumcraftBridge.slug("Ünïcödé Key: 2"));
	}
}
