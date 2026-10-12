package dev.luminary.core.catalog;

import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogServiceTest {
	static final LuminaryId ORE = LuminaryId.parse("luminary:material/first_trace_ore");
	static final LuminaryId ZONE = LuminaryId.parse("luminary:phenomenon/deviation_zone");
	static final LuminaryId EXPERIMENT = LuminaryId.parse("luminary:experiment/compass_drift");
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
	static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
	static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());
	static final UUID ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());

	CatalogState state;
	Templates templates;
	EventBus bus;
	AtomicInteger dirty;
	CatalogService service;
	Instant now = Instant.parse("2026-10-12T00:00:00Z");

	@BeforeEach
	void setUp() {
		state = new CatalogState();
		templates = new Templates()
				.add(new DiscoveryTemplate(ORE, "t.ore", new VerificationRequirement(2, 8, Set.of(), Set.of()), Set.of(), Visibility.PUBLIC_WHEN_VERIFIED))
				.add(new DiscoveryTemplate(ZONE, "t.zone", new VerificationRequirement(3, 0, Set.of(EXPERIMENT), Set.of()), Set.of(ORE), Visibility.PUBLIC_WHEN_VERIFIED));
		templates.validate();
		bus = new EventBus((e, l, t) -> { throw new AssertionError(t); });
		dirty = new AtomicInteger();
		service = new CatalogService(state, templates, bus, Clock.fixed(now, ZoneOffset.UTC), dirty::incrementAndGet);
	}

	Attribution by(UUID player) {
		return new Attribution(player, player.toString().substring(0, 5), now, "minecraft:overworld", 0L);
	}

	@Test
	void firstObserverIsAttributedForeverAndLaterObserversBecomeEvidence() {
		assertTrue(service.observe(ORE, by(ALICE)).ok());
		assertTrue(service.observe(ORE, by(BOB)).ok());
		assertEquals(CatalogService.REASON_DUPLICATE_EVIDENCE, service.observe(ORE, by(BOB)).reasonKey());

		DiscoveryRecord r = state.get(ORE).orElseThrow();
		assertEquals(ALICE, r.firstObserver().playerId());
		assertEquals(DiscoveryStatus.OBSERVED, r.status());
		assertEquals(1, r.evidence().size());
		assertEquals(2, dirty.get(), "only successful mutations mark dirty");
		assertTrue(bus.history().get(0) instanceof DomainEvent.DiscoveryObserved);
	}

	@Test
	void unknownTemplateIsRejected() {
		assertEquals(CatalogService.REASON_UNKNOWN_TEMPLATE, service.observe(LuminaryId.of("nope"), by(ALICE)).reasonKey());
		assertEquals(0, dirty.get());
	}

	@Test
	void verificationNeedsContributorsAndSamples() {
		service.observe(ORE, by(ALICE));
		assertEquals(CatalogService.REASON_NEED_CONTRIBUTORS, service.tryVerify(ORE, ADMIN, false).reasonKey());
		service.observe(ORE, by(BOB));
		assertEquals(CatalogService.REASON_NEED_SAMPLES, service.tryVerify(ORE, ADMIN, false).reasonKey());
		service.addEvidence(ORE, new Evidence.Sample(LuminaryId.of("s1"), BOB, now, LuminaryId.parse("minecraft:raw_iron"), 5));
		assertEquals(CatalogService.REASON_NEED_SAMPLES, service.tryVerify(ORE, ADMIN, false).reasonKey());
		service.addEvidence(ORE, new Evidence.Sample(LuminaryId.of("s2"), ALICE, now, LuminaryId.parse("minecraft:raw_iron"), 3));
		assertTrue(service.tryVerify(ORE, ADMIN, false).ok());
		assertEquals(DiscoveryStatus.VERIFIED, state.get(ORE).orElseThrow().status());
		assertEquals("requirements_met", state.get(ORE).orElseThrow().history().get(1).reason());
		assertEquals(CatalogService.REASON_ALREADY_VERIFIED, service.tryVerify(ORE, ADMIN, false).reasonKey());
	}

	@Test
	void evidenceIsIdempotentOnId() {
		service.observe(ORE, by(ALICE));
		Evidence.Sample s = new Evidence.Sample(LuminaryId.of("s1"), BOB, now, LuminaryId.parse("minecraft:raw_iron"), 5);
		assertTrue(service.addEvidence(ORE, s).ok());
		assertFalse(service.addEvidence(ORE, s).ok());
		assertEquals(1, state.get(ORE).orElseThrow().evidence().size());
	}

	@Test
	void dependencyMustBeVerifiedFirstAndExperimentRequired() {
		service.observe(ZONE, by(ALICE));
		service.observe(ZONE, by(BOB));
		service.observe(ZONE, by(CAROL));
		service.addEvidence(ZONE, new Evidence.Experiment(LuminaryId.of("x1"), CAROL, now, EXPERIMENT, Map.of("drift", "12deg")));
		assertEquals(CatalogService.REASON_DEPENDENCY_UNVERIFIED, service.tryVerify(ZONE, ADMIN, false).reasonKey());

		verifyOre();
		assertTrue(service.tryVerify(ZONE, ADMIN, false).ok());
	}

	@Test
	void experimentRequirementIsEnforced() {
		verifyOre();
		service.observe(ZONE, by(ALICE));
		service.observe(ZONE, by(BOB));
		service.observe(ZONE, by(CAROL));
		assertEquals(CatalogService.REASON_NEED_EXPERIMENT, service.tryVerify(ZONE, ADMIN, false).reasonKey());
	}

	@Test
	void adminOverrideIsRecordedButCannotVerifyUnobserved() {
		assertEquals(CatalogService.REASON_NOT_OBSERVED, service.tryVerify(ORE, ADMIN, true).reasonKey());
		service.observe(ORE, by(ALICE));
		assertTrue(service.tryVerify(ORE, ADMIN, true).ok());
		List<StatusChange> history = state.get(ORE).orElseThrow().history();
		assertEquals("admin_override:" + CatalogService.REASON_NEED_CONTRIBUTORS, history.get(history.size() - 1).reason());
		assertEquals(ADMIN, history.get(history.size() - 1).actor());
	}

	@Test
	void appliedOnlyFromVerifiedAndOnlyOnce() {
		service.observe(ORE, by(ALICE));
		assertEquals(CatalogService.REASON_NOT_VERIFIED, service.markApplied(ORE, LuminaryId.of("tech/smelting")).reasonKey());
		verifyOre();
		assertTrue(service.markApplied(ORE, LuminaryId.of("tech/smelting")).ok());
		assertEquals(CatalogService.REASON_ALREADY_APPLIED, service.markApplied(ORE, LuminaryId.of("tech/smelting")).reasonKey());
		assertEquals(DiscoveryStatus.APPLIED, state.get(ORE).orElseThrow().status());
	}

	private void verifyOre() {
		service.observe(ORE, by(ALICE));
		service.observe(ORE, by(BOB));
		service.addEvidence(ORE, new Evidence.Sample(LuminaryId.of("ore-s"), BOB, now, LuminaryId.parse("minecraft:raw_iron"), 8));
		assertTrue(service.tryVerify(ORE, ADMIN, false).ok());
	}
}
