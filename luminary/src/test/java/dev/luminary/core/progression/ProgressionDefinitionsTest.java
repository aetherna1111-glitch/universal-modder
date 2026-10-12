package dev.luminary.core.progression;

import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionDefinitionsTest {
	static final LuminaryId E0 = LuminaryId.of("era/zero"), E1 = LuminaryId.of("era/one");
	static final LuminaryId A = LuminaryId.of("tech/a"), B = LuminaryId.of("tech/b");
	static final LuminaryId ORE = LuminaryId.of("material/ore");

	static Era era(LuminaryId id, int order, Prerequisite... p) {
		return new Era(id, order, "t", Set.of(p));
	}

	static Technology tech(LuminaryId id, LuminaryId era, Set<Prerequisite> p, Set<LuminaryId> applies) {
		return new Technology(id, era, "t", p, applies, Set.of());
	}

	@Test
	void validDefinitionsPass() {
		ProgressionDefinitions d = new ProgressionDefinitions()
				.addEra(era(E0, 0)).addEra(era(E1, 1, new Prerequisite.DiscoveryVerified(ORE)))
				.addTechnology(tech(A, E1, Set.of(new Prerequisite.EraAtLeast(E1)), Set.of(ORE)))
				.addTechnology(tech(B, E1, Set.of(new Prerequisite.TechnologyUnlocked(A)), Set.of()));
		assertDoesNotThrow(() -> d.validate(Set.of(ORE)));
		assertEquals(E0, d.firstEra().orElseThrow().id());
		assertEquals(E1, d.eraAfter(E0).orElseThrow().id());
		assertTrue(d.eraAfter(E1).isEmpty());
	}

	@Test
	void rejectsUnknownReferencesDuplicatesAndBadFirstEra() {
		assertThrows(IllegalArgumentException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0)).addEra(era(E0, 1)));
		assertThrows(IllegalStateException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0)).addEra(era(E1, 0)).validate(Set.of()));
		assertThrows(IllegalStateException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0, new Prerequisite.DiscoveryVerified(ORE))).validate(Set.of(ORE)));
		assertThrows(IllegalStateException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0))
				.addTechnology(tech(A, E1, Set.of(), Set.of())).validate(Set.of()));
		assertThrows(IllegalStateException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0))
				.addTechnology(tech(A, E0, Set.of(new Prerequisite.DiscoveryVerified(ORE)), Set.of())).validate(Set.of()));
		assertThrows(IllegalStateException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0))
				.addTechnology(tech(A, E0, Set.of(), Set.of(ORE))).validate(Set.of()));
		assertThrows(IllegalStateException.class, () -> new ProgressionDefinitions().addEra(era(E0, 0))
				.addTechnology(tech(A, E0, Set.of(new Prerequisite.TechnologyUnlocked(B)), Set.of())).validate(Set.of()));
	}

	@Test
	void rejectsCycles() {
		ProgressionDefinitions d = new ProgressionDefinitions().addEra(era(E0, 0))
				.addTechnology(tech(A, E0, Set.of(new Prerequisite.TechnologyUnlocked(B)), Set.of()))
				.addTechnology(tech(B, E0, Set.of(new Prerequisite.TechnologyUnlocked(A)), Set.of()));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> d.validate(Set.of()));
		assertTrue(e.getMessage().contains("cycle"));
		assertThrows(IllegalArgumentException.class, () -> tech(A, E0, Set.of(new Prerequisite.TechnologyUnlocked(A)), Set.of()));
	}
}
