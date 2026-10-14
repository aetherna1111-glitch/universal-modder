package dev.luminary.core.npc.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure self-defence state machine, clock values injected. One opponent at a time (the latest attacker); hits are
 * counted inside the attack window; a declared duel skips the warning but still ends by the same rules. Outputs are
 * {@link Reaction}s the body performs; the machine never knows about entities or damage numbers.
 * <p>No revenge: leaving {@link CombatState#COMBAT} is final until a new hit arrives; the NPC never seeks out a
 * player it fought.</p>
 */
public final class CombatMachine {
	public enum Reaction { NONE, RAISE_SHIELD, WARN, FIGHT, STAND_DOWN, RECOVERED }

	public record Transition(CombatState from, CombatState to, Reaction reaction) {
		public boolean changed() {
			return from != to;
		}
	}

	private final CombatRules rules;
	private CombatState state = CombatState.CALM;
	private UUID opponent;
	private final Deque<Instant> hits = new ArrayDeque<>();
	private Instant stateSince = Instant.EPOCH;
	private Instant opponentLastNear = Instant.EPOCH;
	private UUID duelPartner;
	private Instant duelConsentUntil = Instant.EPOCH;
	private boolean duelActive;

	public CombatMachine(CombatRules rules) {
		this.rules = rules;
	}

	public CombatRules rules() {
		return rules;
	}

	public CombatState state() {
		return state;
	}

	public Optional<UUID> opponent() {
		return Optional.ofNullable(opponent);
	}

	public Instant stateSince() {
		return stateSince;
	}

	public boolean inDuelWith(UUID player) {
		return duelActive && player != null && player.equals(duelPartner);
	}

	/** The player proposed a duel and the NPC accepted: hits from them inside the consent window start a fight without warnings and without karma loss. */
	public void acceptDuel(UUID player, Instant now) {
		duelPartner = player;
		duelConsentUntil = now.plus(rules.duelConsentWindow());
		duelActive = false;
	}

	public boolean duelPending(UUID player, Instant now) {
		return player != null && player.equals(duelPartner) && !now.isAfter(duelConsentUntil) && !duelActive;
	}

	/** A player hit the NPC. */
	public Transition onAttacked(UUID attacker, Instant now) {
		CombatState from = state;
		if (duelPending(attacker, now) || inDuelWith(attacker)) {
			duelActive = true;
			opponent = attacker;
			opponentLastNear = now;
			hits.clear();
			return enter(CombatState.COMBAT, now, from == CombatState.COMBAT ? Reaction.NONE : Reaction.FIGHT, from);
		}
		if (opponent != null && !opponent.equals(attacker)) {
			hits.clear();   // a new attacker starts their own count
		}
		opponent = attacker;
		opponentLastNear = now;
		hits.addLast(now);
		pruneHits(now);
		switch (state) {
			case CALM, RECOVERY, GUARDED_DEESCALATION -> {
				if (hits.size() >= rules.attacksBeforeCombat()) {
					return enter(CombatState.COMBAT, now, Reaction.FIGHT, from);
				}
				return enter(CombatState.DEFENSIVE_WARNING, now, rules.shieldFirst() ? Reaction.RAISE_SHIELD : Reaction.WARN, from);
			}
			case DEFENSIVE_WARNING -> {
				if (hits.size() >= rules.attacksBeforeCombat()) {
					return enter(CombatState.COMBAT, now, Reaction.FIGHT, from);
				}
				stateSince = now;   // the warning clock restarts on every hit
				return new Transition(from, state, Reaction.WARN);
			}
			case COMBAT -> {
				return new Transition(from, state, Reaction.NONE);
			}
		}
		return new Transition(from, state, Reaction.NONE);
	}

	/** The opponent said sorry. Forgiven at once from warning or guard; from combat the NPC lowers the weapon and guards. */
	public Transition onApology(UUID player, Instant now) {
		CombatState from = state;
		if (opponent == null || !opponent.equals(player) || state == CombatState.CALM) {
			return new Transition(from, state, Reaction.NONE);
		}
		hits.clear();
		duelActive = false;
		return switch (state) {
			case DEFENSIVE_WARNING, GUARDED_DEESCALATION -> enter(CombatState.RECOVERY, now, Reaction.STAND_DOWN, from);
			case COMBAT -> enter(CombatState.GUARDED_DEESCALATION, now, Reaction.STAND_DOWN, from);
			default -> new Transition(from, state, Reaction.NONE);
		};
	}

	/** The body went down: whatever was happening ends; recovery starts when the body gets up (see {@link #onRecovered}). */
	public Transition onKnockedOut(Instant now) {
		CombatState from = state;
		hits.clear();
		duelActive = false;
		return enter(CombatState.RECOVERY, now, Reaction.STAND_DOWN, from);
	}

	/** Back on its feet: recovery timer restarts from now. */
	public void onRecovered(Instant now) {
		stateSince = now;
		state = CombatState.RECOVERY;
	}

	/**
	 * Per-tick evaluation. {@code opponentDistance} is empty when the opponent is gone (offline, dead, other world).
	 */
	public Transition tick(Optional<Double> opponentDistance, Instant now) {
		CombatState from = state;
		if (opponentDistance.isPresent() && opponentDistance.get() <= rules.deescalateDistance()) {
			opponentLastNear = now;
		}
		boolean opponentAway = opponentDistance.isEmpty() || Duration.between(opponentLastNear, now).compareTo(rules.deescalateAfter()) >= 0;
		switch (state) {
			case CALM -> {
				return new Transition(from, state, Reaction.NONE);
			}
			case DEFENSIVE_WARNING -> {
				pruneHits(now);
				if (opponentAway || hits.isEmpty()) {
					return enter(CombatState.GUARDED_DEESCALATION, now, Reaction.NONE, from);
				}
			}
			case GUARDED_DEESCALATION -> {
				if (Duration.between(stateSince, now).compareTo(rules.deescalateAfter()) >= 0) {
					return enter(CombatState.RECOVERY, now, Reaction.STAND_DOWN, from);
				}
			}
			case COMBAT -> {
				if (opponentAway) {
					hits.clear();
					duelActive = false;
					return enter(CombatState.RECOVERY, now, Reaction.STAND_DOWN, from);
				}
			}
			case RECOVERY -> {
				if (Duration.between(stateSince, now).compareTo(rules.recovery()) >= 0) {
					opponent = null;
					hits.clear();
					return enter(CombatState.CALM, now, Reaction.RECOVERED, from);
				}
			}
		}
		return new Transition(from, state, Reaction.NONE);
	}

	/** Whether the body should strike the opponent now: only in COMBAT and only within reach. */
	public boolean shouldStrike(double opponentDistance) {
		return state == CombatState.COMBAT && opponentDistance <= rules.strikeReach();
	}

	public boolean shieldUp() {
		return state == CombatState.DEFENSIVE_WARNING || state == CombatState.GUARDED_DEESCALATION || state == CombatState.COMBAT;
	}

	public int recentHits() {
		return hits.size();
	}

	private void pruneHits(Instant now) {
		while (!hits.isEmpty() && Duration.between(hits.peekFirst(), now).compareTo(rules.attackWindow()) > 0) {
			hits.removeFirst();
		}
	}

	private Transition enter(CombatState to, Instant now, Reaction reaction, CombatState from) {
		state = to;
		stateSince = now;
		return new Transition(from, to, reaction);
	}

	/** Codec only. */
	public void restore(CombatState s, UUID opponent, Instant since) {
		this.state = s;
		this.opponent = opponent;
		this.stateSince = since;
		this.opponentLastNear = since;
	}
}
