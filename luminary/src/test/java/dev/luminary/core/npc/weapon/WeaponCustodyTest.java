package dev.luminary.core.npc.weapon;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WeaponCustodyTest {
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");
	private final UUID first = UUID.randomUUID();
	private final UUID thief = UUID.randomUUID();
	private final UUID other = UUID.randomUUID();

	@Test
	void theftLastsExactlyOneHourAndOnlyTheThiefMayHoldIt() {
		WeaponCustody w = new WeaponCustody(first);
		assertTrue(w.isCurrent(first));
		assertFalse(w.mayBeHeldBy(first, thief), "while in the NPC's custody nobody may hold a copy");
		WeaponCustody.State s = w.onTaken(thief, t0, WeaponCustody.DEFAULT_THEFT_DURATION).orElseThrow();
		assertEquals(t0.plus(Duration.ofHours(1)), s.returnAt().orElseThrow());
		assertEquals(WeaponCustody.Custody.STOLEN, w.custody());
		assertTrue(w.mayBeHeldBy(first, thief));
		assertFalse(w.mayBeHeldBy(first, other), "handing the stolen blade to a friend does not launder it");
		assertFalse(w.dueForReturn(t0.plusSeconds(3599)));
		assertTrue(w.dueForReturn(t0.plusSeconds(3600)));
		assertTrue(w.onTaken(other, t0.plusSeconds(5), Duration.ofHours(1)).isEmpty(), "cannot be stolen twice");
	}

	@Test
	void returnMintsANewInstanceAndRetiresTheOldOneForever() {
		WeaponCustody w = new WeaponCustody(first);
		w.onTaken(thief, t0, Duration.ofHours(1));
		UUID second = UUID.randomUUID();
		assertEquals(Optional.of(first), w.returned(second, t0.plus(Duration.ofHours(1))));
		assertEquals(WeaponCustody.Custody.NPC, w.custody());
		assertTrue(w.isCurrent(second));
		assertFalse(w.isCurrent(first), "the stale copy is never valid again, wherever it sits");
		assertFalse(w.mayBeHeldBy(first, thief));
		assertEquals(1, w.state().thefts());
		assertEquals(1, w.state().returns());
		assertTrue(w.returned(UUID.randomUUID(), t0).isEmpty(), "nothing to return while in custody");
		assertTrue(w.state().holder().isEmpty());
	}

	@Test
	void permanentTransferIsTheOnlyWayToKeepItAndSurvivesTheClock() {
		WeaponCustody w = new WeaponCustody(first);
		WeaponCustody.State s = w.transferPermanently(other, "quest:unspecified", t0);
		assertEquals(WeaponCustody.Custody.TRANSFERRED, s.custody());
		assertTrue(w.mayBeHeldBy(first, other));
		assertFalse(w.dueForReturn(t0.plus(Duration.ofHours(100))));
		assertTrue(w.returned(UUID.randomUUID(), t0.plus(Duration.ofHours(2))).isEmpty(), "a transferred blade does not fly home");
		assertTrue(w.onTaken(thief, t0, Duration.ofHours(1)).isEmpty());
		UUID fresh = UUID.randomUUID();
		assertEquals(first, w.reclaim(fresh));
		assertEquals(WeaponCustody.Custody.NPC, w.custody());
		assertFalse(w.mayBeHeldBy(first, other));
	}

	@Test
	void restoredStateIsHonoured() {
		WeaponCustody.State s = new WeaponCustody.State(first, WeaponCustody.Custody.STOLEN, Optional.of(thief), Optional.of(t0), Optional.of(t0.plus(Duration.ofHours(1))), Optional.empty(), 1, 0);
		WeaponCustody w = new WeaponCustody(s);
		assertTrue(w.dueForReturn(t0.plus(Duration.ofHours(1))), "a restart inside the hour keeps the deadline");
	}

	@Test
	void aTransferredBladeIsOwedBackToItsOwnerUntilDelivered() {
		WeaponCustody w = new WeaponCustody(first);
		assertFalse(w.markReturnToOwner(t0), "nothing to return while the NPC holds it");
		w.transferPermanently(thief, "quest", t0);
		assertTrue(w.mayBeHeldBy(first, thief), "the new owner may hold it");
		assertFalse(w.mayBeHeldBy(first, other), "nobody else may");
		assertFalse(w.returnToOwnerDue());
		assertTrue(w.markReturnToOwner(t0.plusSeconds(5)));
		assertFalse(w.markReturnToOwner(t0.plusSeconds(6)), "marked once");
		assertTrue(w.returnToOwnerDue());
		assertEquals(WeaponCustody.Custody.TRANSFERRED, w.custody());
		assertTrue(w.dueForReturn(t0.plusSeconds(99999)) == false, "the NPC's one-hour return never applies to a transferred blade");
		w.ownerReceived();
		assertFalse(w.returnToOwnerDue());
		assertEquals(1, w.state().returns());
	}
}
