package dev.luminary.core.npc.memory;

import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcRoster;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcMemoryObserverTest {
	@Test
	void everyDefinedNpcWitnessesCivilizationEventsOnce() {
		NpcRoster roster = new NpcRoster();
		LuminaryId a = LuminaryId.of("npc/a"), b = LuminaryId.of("npc/b");
		List<NpcDefinition> npcs = List.of(new NpcDefinition(a, "n", LuminaryId.of("d")), new NpcDefinition(b, "n", LuminaryId.of("d")));
		EventBus bus = new EventBus((e, l, t) -> { throw new AssertionError(t); });
		AtomicInteger dirty = new AtomicInteger();
		new NpcMemoryObserver(() -> npcs, roster, dirty::incrementAndGet).subscribe(bus);
		UUID alice = UUID.nameUUIDFromBytes("alice".getBytes());
		Instant t = Instant.parse("2026-10-12T00:00:00Z");
		LuminaryId ore = LuminaryId.of("material/ore");

		bus.publish(new DomainEvent.DiscoveryObserved(t, ore, alice));
		bus.publish(new DomainEvent.DiscoveryStatusChanged(t, ore, DiscoveryStatus.OBSERVED, DiscoveryStatus.VERIFIED, alice));
		bus.publish(new DomainEvent.DiscoveryStatusChanged(t.plusSeconds(5), ore, DiscoveryStatus.VERIFIED, DiscoveryStatus.APPLIED, null));
		bus.publish(new DomainEvent.EraEntered(t, LuminaryId.of("era/one"), null));

		for (LuminaryId id : List.of(a, b)) {
			MemoryStore m = roster.get(id).orElseThrow().memory();
			assertEquals(1, m.forPlayer(alice).size(), "the observer credits the first observer");
			assertEquals(MemoryKind.OBSERVATION, m.forPlayer(alice).get(0).kind());
			List<MemoryEntry> status = m.all().stream().filter(e -> e.topicKey().map(k -> k.startsWith("discovery_status/")).orElse(false)).toList();
			assertEquals(1, status.size(), "same topic: the APPLIED status superseded VERIFIED");
			assertTrue(status.get(0).text().endsWith("APPLIED"));
			assertTrue(m.all().stream().allMatch(e -> e.provenance() == Provenance.OBSERVED_IN_WORLD));
		}
		assertEquals(4, dirty.get());
	}
}
