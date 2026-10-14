package dev.luminary.core.npc.weapon;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative identity of one NPC's unique weapon. Exactly one {@code instanceId} is valid at a time; any
 * item stack carrying another id is a stale copy and must vanish when the world next sees it (reconciliation). A
 * stolen weapon has a UTC {@code returnAt} after exactly {@code theftDuration}; when it passes the weapon returns to
 * the NPC "supernaturally": the current instance is retired, a new one is minted, so no stale copy can ever be used
 * again, wherever it sits (a chest, an offline inventory, the ground). A permanent transfer is possible only through
 * {@link #transferPermanently}, the single integration point for a quest the game has not written yet.
 */
public final class WeaponCustody {
	public enum Custody { NPC, STOLEN, TRANSFERRED }

	public record State(UUID instanceId, Custody custody, Optional<UUID> holder, Optional<Instant> takenAt, Optional<Instant> returnAt,
						Optional<String> transferReason, int thefts, int returns) {
		public State {
			Objects.requireNonNull(instanceId);
			Objects.requireNonNull(custody);
			Objects.requireNonNull(holder);
			Objects.requireNonNull(takenAt);
			Objects.requireNonNull(returnAt);
			Objects.requireNonNull(transferReason);
		}
	}

	public static final Duration DEFAULT_THEFT_DURATION = Duration.ofHours(1);

	private State state;

	public WeaponCustody(UUID firstInstance) {
		this.state = new State(firstInstance, Custody.NPC, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
	}

	public WeaponCustody(State restored) {
		this.state = Objects.requireNonNull(restored);
	}

	public State state() {
		return state;
	}

	public UUID instanceId() {
		return state.instanceId();
	}

	public Custody custody() {
		return state.custody();
	}

	/** True for the one live instance. Stale copies (older ids) are never valid. */
	public boolean isCurrent(UUID instance) {
		return state.instanceId().equals(instance);
	}

	/** Whether a copy of {@code instance} may sit in {@code holder}'s hands right now (the thief during the theft window, or the permanent owner). */
	public boolean mayBeHeldBy(UUID instance, UUID holder) {
		return isCurrent(instance) && state.custody() != Custody.NPC && state.holder().filter(h -> h.equals(holder)).isPresent();
	}

	/** A player took the weapon from the downed NPC. Refused while already stolen or transferred. */
	public Optional<State> onTaken(UUID thief, Instant now, Duration theftDuration) {
		if (state.custody() != Custody.NPC) {
			return Optional.empty();
		}
		state = new State(state.instanceId(), Custody.STOLEN, Optional.of(thief), Optional.of(now), Optional.of(now.plus(theftDuration)),
				Optional.empty(), state.thefts() + 1, state.returns());
		return Optional.of(state);
	}

	public boolean dueForReturn(Instant now) {
		return state.custody() == Custody.STOLEN && state.returnAt().filter(r -> !now.isBefore(r)).isPresent();
	}

	/**
	 * The weapon returns to the NPC (expiry, or voluntary return). Retires the current instance and mints a new one;
	 * returns the retired id so the caller can purge copies it can reach. Refused when transferred permanently.
	 */
	public Optional<UUID> returned(UUID newInstance, Instant now) {
		if (state.custody() != Custody.STOLEN) {
			return Optional.empty();
		}
		UUID retired = state.instanceId();
		state = new State(newInstance, Custody.NPC, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), state.thefts(), state.returns() + 1);
		return Optional.of(retired);
	}

	/** The one authorized way to hand the weapon over for good. {@code reason} names the quest or decision; it is recorded, not interpreted. */
	public State transferPermanently(UUID newOwner, String reason, Instant now) {
		state = new State(state.instanceId(), Custody.TRANSFERRED, Optional.of(newOwner), Optional.of(now), Optional.empty(),
				Optional.of(Objects.requireNonNull(reason)), state.thefts(), state.returns());
		return state;
	}

	/**
	 * A transferred blade left the owner's hands (someone else picked it up, or it lay on the ground for its hour):
	 * it is owed back to the owner. Recorded in {@code returnAt} = now, until the owner receives it. Refused unless transferred.
	 */
	public boolean markReturnToOwner(Instant now) {
		if (state.custody() != Custody.TRANSFERRED || state.returnAt().isPresent()) {
			return false;
		}
		state = new State(state.instanceId(), state.custody(), state.holder(), state.takenAt(), Optional.of(now), state.transferReason(), state.thefts(), state.returns() );
		return true;
	}

	/** Is a transferred blade owed back to its owner right now? */
	public boolean returnToOwnerDue() {
		return state.custody() == Custody.TRANSFERRED && state.returnAt().isPresent();
	}

	/** The owner received the blade back. */
	public void ownerReceived() {
		if (state.custody() == Custody.TRANSFERRED) {
			state = new State(state.instanceId(), state.custody(), state.holder(), state.takenAt(), Optional.empty(), state.transferReason(), state.thefts(), state.returns() + 1);
		}
	}

	/** Reverts a permanent transfer (admin): the NPC gets a fresh instance, the holder's copy becomes stale. */
	public UUID reclaim(UUID newInstance) {
		UUID retired = state.instanceId();
		state = new State(newInstance, Custody.NPC, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), state.thefts(), state.returns());
		return retired;
	}
}
