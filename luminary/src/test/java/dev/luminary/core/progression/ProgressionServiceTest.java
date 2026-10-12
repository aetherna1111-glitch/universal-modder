package dev.luminary.core.progression;

import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.catalog.CatalogState;
import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.catalog.DiscoveryTemplate;
import dev.luminary.core.catalog.Templates;
import dev.luminary.core.catalog.VerificationRequirement;
import dev.luminary.core.catalog.Visibility;
import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionServiceTest {
	static final LuminaryId E0 = LuminaryId.of("era/zero"), E1 = LuminaryId.of("era/settlement"), E2 = LuminaryId.of("era/town");
	static final LuminaryId ORE = LuminaryId.of("material/ore"), ZONE = LuminaryId.of("phenomenon/zone");
	static final LuminaryId ASSAY = LuminaryId.of("tech/assay"), SURVEY = LuminaryId.of("tech/survey");
	static final LuminaryId FOUNDED = LuminaryId.of("signal/luminarium_founded");
	static final LuminaryId CAP = LuminaryId.of("cap/assay_bench");
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes()), ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());
	static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

	EventBus bus;
	CatalogService catalog;
	ProgressionService progression;
	AtomicInteger dirty;

	@BeforeEach
	void setUp() {
		bus = new EventBus((e, l, t) -> { throw new AssertionError(t); });
		dirty = new AtomicInteger();
		Templates templates = new Templates()
				.add(new DiscoveryTemplate(ORE, "t", VerificationRequirement.NONE, Set.of(), Visibility.PUBLIC_WHEN_VERIFIED))
				.add(new DiscoveryTemplate(ZONE, "t", VerificationRequirement.NONE, Set.of(), Visibility.PUBLIC_WHEN_VERIFIED));
		catalog = new CatalogService(new CatalogState(), templates, bus, Clock.fixed(NOW, ZoneOffset.UTC), dirty::incrementAndGet);
		ProgressionDefinitions defs = new ProgressionDefinitions()
				.addEra(new Era(E0, 0, "t", Set.of()))
				.addEra(new Era(E1, 1, "t", Set.of(new Prerequisite.DiscoveryVerified(ORE))))
				.addEra(new Era(E2, 2, "t", Set.of(new Prerequisite.TechnologyUnlocked(ASSAY), new Prerequisite.Signal(FOUNDED, 1))))
				.addTechnology(new Technology(ASSAY, E1, "t", Set.of(new Prerequisite.EraAtLeast(E1), new Prerequisite.DiscoveryVerified(ORE)), Set.of(ORE), Set.of(CAP)))
				.addTechnology(new Technology(SURVEY, E2, "t", Set.of(new Prerequisite.EraAtLeast(E2), new Prerequisite.TechnologyUnlocked(ASSAY),
						new Prerequisite.DiscoveryVerified(ZONE)), Set.of(ZONE), Set.of()));
		defs.validate(Set.of(ORE, ZONE));
		progression = new ProgressionService(new ProgressionState(), defs, catalog, bus, Clock.fixed(NOW, ZoneOffset.UTC), dirty::incrementAndGet);
		progression.start();
	}

	private void verify(LuminaryId discovery) {
		catalog.observe(discovery, new Attribution(ALICE, "Alice", NOW, "minecraft:overworld", 0L));
		assertTrue(catalog.tryVerify(discovery, ADMIN, false).ok());
	}

	@Test
	void startsInFirstEraWithNothingUnlocked() {
		assertEquals(E0, progression.state().currentEra().orElseThrow());
		assertTrue(progression.state().unlocked().isEmpty());
		assertTrue(bus.history().get(0) instanceof DomainEvent.EraEntered);
	}

	@Test
	void verifyingADiscoveryCascadesIntoEraAndTechnologyAndApplied() {
		verify(ORE);   // Catalog event → auto evaluation: era settlement → assay unlock → ORE applied
		assertEquals(E1, progression.state().currentEra().orElseThrow());
		assertTrue(progression.state().isUnlocked(ASSAY));
		assertEquals("prerequisites_met", progression.state().unlocked().get(ASSAY).reason());
		assertEquals(DiscoveryStatus.APPLIED, catalog.state().get(ORE).orElseThrow().status());
		assertTrue(progression.hasCapability(CAP));
		assertFalse(progression.state().isUnlocked(SURVEY), "town era not reached: signal missing");
	}

	@Test
	void signalsAdvanceErasAndUnlocksAreIdempotent() {
		verify(ORE);
		verify(ZONE);
		assertEquals(E1, progression.state().currentEra().orElseThrow());
		progression.setSignal(FOUNDED, 1, "test");
		assertEquals(E2, progression.state().currentEra().orElseThrow());
		assertTrue(progression.state().isUnlocked(SURVEY));
		assertEquals(DiscoveryStatus.APPLIED, catalog.state().get(ZONE).orElseThrow().status());

		int unlocks = progression.state().unlocked().size();
		int before = dirty.get();
		assertTrue(progression.applyAutoUnlocks().isEmpty());
		progression.setSignal(FOUNDED, 1, "test");
		assertEquals(unlocks, progression.state().unlocked().size());
		assertEquals(before, dirty.get(), "no-op evaluations must not mark dirty");
		assertEquals(ProgressionService.REASON_ALREADY_UNLOCKED, progression.unlock(SURVEY, ADMIN, false).reasonKey());
	}

	@Test
	void explicitUnlockRespectsPrerequisitesUnlessForcedAndRecordsIt() {
		assertEquals(ProgressionService.REASON_UNKNOWN_TECHNOLOGY, progression.unlock(LuminaryId.of("tech/nope"), ADMIN, false).reasonKey());
		assertEquals(ProgressionService.REASON_PREREQUISITES_UNMET, progression.unlock(ASSAY, ADMIN, false).reasonKey());
		assertTrue(progression.unlock(ASSAY, ADMIN, true).ok());
		ProgressionState.Unlock u = progression.state().unlocked().get(ASSAY);
		assertTrue(u.reason().startsWith("admin_override:"));
		assertEquals(ADMIN, u.actor());
		// ORE was never observed, so it cannot be APPLIED even by a forced unlock; capability still granted.
		assertTrue(catalog.state().get(ORE).isEmpty());
		assertTrue(progression.hasCapability(CAP));
		// a forced unlock counts as a prerequisite for others, but the era did not advance (ORE not verified)
		assertEquals(E0, progression.state().currentEra().orElseThrow());
	}

	@Test
	void missingListsExactlyTheUnmetPrerequisites() {
		Technology survey = progression.definitions().technology(SURVEY).orElseThrow();
		List<Prerequisite> missing = progression.missing(survey);
		assertEquals(3, missing.size());
		verify(ORE);
		assertEquals(2, progression.missing(survey).size()); // era town + zone
	}
}
