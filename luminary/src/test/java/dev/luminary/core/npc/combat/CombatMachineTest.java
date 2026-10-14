package dev.luminary.core.npc.combat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatMachineTest {
	private final CombatRules rules = CombatRules.DEFAULTS;   // 3 attacks, 60 s window, 8 blocks, 20 s, 30 s recovery
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");
	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();

	@Test
	void shieldFirstThenWarningsThenCombatAtTheThreshold() {
		CombatMachine m = new CombatMachine(rules);
		CombatMachine.Transition t1 = m.onAttacked(a, t0);
		assertEquals(CombatState.DEFENSIVE_WARNING, t1.to());
		assertEquals(CombatMachine.Reaction.RAISE_SHIELD, t1.reaction());
		assertTrue(m.shieldUp());
		assertFalse(m.shouldStrike(1.0), "no strike while only warning");
		CombatMachine.Transition t2 = m.onAttacked(a, t0.plusSeconds(5));
		assertEquals(CombatMachine.Reaction.WARN, t2.reaction());
		assertEquals(CombatState.DEFENSIVE_WARNING, t2.to());
		CombatMachine.Transition t3 = m.onAttacked(a, t0.plusSeconds(10));
		assertEquals(CombatState.COMBAT, t3.to());
		assertEquals(CombatMachine.Reaction.FIGHT, t3.reaction());
		assertTrue(m.shouldStrike(3.0));
		assertFalse(m.shouldStrike(3.6), "beyond reach: no strike, no pursuit");
	}

	@Test
	void thresholdIsConfigurableAndHitsExpireWithTheWindow() {
		CombatMachine five = new CombatMachine(new CombatRules(5, Duration.ofSeconds(60), 8, Duration.ofSeconds(20), Duration.ofSeconds(30), 0.5, 3.5, 6, Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(90), true));
		for (int i = 0; i < 4; i++) five.onAttacked(a, t0.plusSeconds(i));
		assertEquals(CombatState.DEFENSIVE_WARNING, five.state());
		assertEquals(CombatState.COMBAT, five.onAttacked(a, t0.plusSeconds(4)).to());
		CombatMachine m = new CombatMachine(rules);
		m.onAttacked(a, t0);
		m.onAttacked(a, t0.plusSeconds(1));
		m.tick(Optional.of(2.0), t0.plusSeconds(62));   // window passed for both hits, opponent still near
		assertEquals(CombatState.GUARDED_DEESCALATION, m.state(), "old hits are forgotten");
		assertEquals(CombatState.DEFENSIVE_WARNING, m.onAttacked(a, t0.plusSeconds(63)).to(), "a hit after the window starts a fresh count");
	}

	@Test
	void retreatDeescalatesAndRecoveryEndsInCalmWithoutRevenge() {
		CombatMachine m = new CombatMachine(rules);
		for (int i = 0; i < 3; i++) m.onAttacked(a, t0.plusSeconds(i));
		assertEquals(CombatState.COMBAT, m.state());
		m.tick(Optional.of(12.0), t0.plusSeconds(10));
		assertEquals(CombatState.COMBAT, m.state(), "just stepped back: still fighting for the de-escalation window");
		CombatMachine.Transition away = m.tick(Optional.of(12.0), t0.plusSeconds(23));
		assertEquals(CombatState.RECOVERY, away.to());
		assertEquals(CombatMachine.Reaction.STAND_DOWN, away.reaction());
		assertFalse(m.shouldStrike(1.0), "the opponent coming back inside reach during recovery is not struck");
		CombatMachine.Transition calm = m.tick(Optional.empty(), t0.plusSeconds(54));
		assertEquals(CombatState.CALM, calm.to());
		assertEquals(CombatMachine.Reaction.RECOVERED, calm.reaction());
		assertTrue(m.opponent().isEmpty(), "no grudge target kept");
	}

	@Test
	void apologyLowersTheWeaponAndADeclaredDuelSkipsTheWarnings() {
		CombatMachine m = new CombatMachine(rules);
		for (int i = 0; i < 3; i++) m.onAttacked(a, t0.plusSeconds(i));
		assertEquals(CombatMachine.Reaction.NONE, m.onApology(b, t0.plusSeconds(4)).reaction(), "a bystander's apology changes nothing");
		CombatMachine.Transition sorry = m.onApology(a, t0.plusSeconds(4));
		assertEquals(CombatState.GUARDED_DEESCALATION, sorry.to());
		assertEquals(CombatMachine.Reaction.STAND_DOWN, sorry.reaction());
		assertEquals(CombatState.RECOVERY, m.tick(Optional.of(2.0), t0.plusSeconds(25)).to());
		CombatMachine d = new CombatMachine(rules);
		d.acceptDuel(a, t0);
		assertTrue(d.duelPending(a, t0.plusSeconds(30)));
		assertFalse(d.duelPending(a, t0.plusSeconds(91)), "consent expires");
		CombatMachine.Transition hit = d.onAttacked(a, t0.plusSeconds(10));
		assertEquals(CombatState.COMBAT, hit.to());
		assertTrue(d.inDuelWith(a));
	}

	@Test
	void knockoutEndsEverythingAndANewAttackerStartsTheirOwnCount() {
		CombatMachine m = new CombatMachine(rules);
		m.onAttacked(a, t0);
		m.onAttacked(a, t0.plusSeconds(1));
		assertEquals(CombatState.DEFENSIVE_WARNING, m.onAttacked(b, t0.plusSeconds(2)).to(), "b's first hit, not a's third");
		assertEquals(b, m.opponent().orElseThrow());
		assertEquals(CombatState.RECOVERY, m.onKnockedOut(t0.plusSeconds(3)).to());
		assertEquals(0, m.recentHits());
	}
}
