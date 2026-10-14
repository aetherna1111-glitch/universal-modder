package dev.luminary.core.npc.capability;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryTest {
	@Test
	void theModelMayOnlyRequestTheAllowlist() {
		Set<Capability> allowed = CapabilityRegistry.modelAllowed();
		assertEquals(EnumSet.of(Capability.SPEAK, Capability.REMEMBER_NOTE, Capability.REVEAL_ALLOWED_CLUE, Capability.GATHER_WOOD, Capability.DEPOSIT,
				Capability.WITHDRAW, Capability.SMELT, Capability.MOVE_TO, Capability.USE_CONTAINER, Capability.FORGET_CONTAINER), allowed);
		for (Capability c : EnumSet.of(Capability.DEFEND, Capability.WEAPON_TRANSFER, Capability.STORY_WRITE, Capability.PROGRESSION_WRITE, Capability.WORLD_EDIT, Capability.TELEPORT, Capability.ADMIN)) {
			assertFalse(c.modelMayRequest(), c + " must never be model-requestable");
		}
	}

	@Test
	void unknownAndForbiddenIntentNamesAreRefusedAndCounted() {
		CapabilityRegistry r = new CapabilityRegistry();
		assertEquals(Capability.GATHER_WOOD, r.forModelIntent(" Gather_Wood ").orElseThrow());
		assertEquals(Capability.SPEAK, r.forModelIntent("none").orElseThrow());
		assertTrue(r.forModelIntent("attack_player").isEmpty());
		assertTrue(r.forModelIntent("transfer_weapon").isEmpty());
		assertTrue(r.forModelIntent("").isEmpty());
		assertTrue(r.forModelIntent(null).isEmpty());
		assertEquals(1L, r.refused().get("attack_player"));
		assertEquals(2L, r.refused().get("(empty)"));
		assertEquals(1L, r.granted().get(Capability.GATHER_WOOD));
		assertEquals(Set.of("deposit", "forget_container", "gather_wood", "move_to", "none", "smelt", "use_container", "withdraw"), CapabilityRegistry.modelIntentNames());
	}
}
