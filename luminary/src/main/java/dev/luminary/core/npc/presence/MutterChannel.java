package dev.luminary.core.npc.presence;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The spatial whisper channel: short authored mutterings tied to what the NPC is actually doing, audible only to
 * players standing within {@code range} blocks. Rate-limited by a cooldown with deterministic jitter so the NPC is
 * mostly silent; nothing is said when nobody is near; nothing is said while a conversation is in progress.
 * Pure: the caller supplies the clock value, the activity and whether listeners are present.
 */
public final class MutterChannel {
	public record Rules(double range, Duration cooldown, Duration jitter, boolean enabled) {
		public static final Rules DEFAULTS = new Rules(5.0, Duration.ofSeconds(90), Duration.ofSeconds(45), true);

		public Rules {
			if (range < 0 || cooldown.isNegative() || jitter.isNegative()) {
				throw new IllegalArgumentException("invalid mutter rules");
			}
		}
	}

	/** What to whisper: the authored line group and the rotation seed. */
	public record Mutter(String lineKey, long sequence, Activity activity) {
	}

	private final Rules rules;
	private Instant nextAllowed = Instant.EPOCH;
	private Instant silentUntil = Instant.EPOCH;
	private long sequence;
	private Activity lastActivity = Activity.IDLE;

	public MutterChannel(Rules rules) {
		this.rules = rules;
	}

	public Rules rules() {
		return rules;
	}

	/** A conversation is happening: hold the whispers for a while so they never talk over the player. */
	public void holdFor(Duration d, Instant now) {
		silentUntil = now.plus(d);
	}

	/**
	 * @param listenersNear whether any player is within {@link Rules#range()} right now
	 * @return a mutter when it is time; also when the activity just changed (a half-cooldown "transition" whisper)
	 */
	public Optional<Mutter> tick(Activity activity, boolean listenersNear, Instant now) {
		boolean changed = activity != lastActivity;
		lastActivity = activity;
		if (!rules.enabled() || !listenersNear || now.isBefore(silentUntil)) {
			return Optional.empty();
		}
		if (now.isBefore(nextAllowed)) {
			return Optional.empty();
		}
		long seq = sequence++;
		// deterministic jitter: spreads whispers without a random source the tests cannot control
		long jitterMillis = rules.jitter().toMillis() == 0 ? 0 : Math.floorMod(seq * 7919L, rules.jitter().toMillis());
		nextAllowed = now.plus(rules.cooldown()).plusMillis(jitterMillis);
		return Optional.of(new Mutter(activity.mutterKey(), seq, activity));
	}

	public Instant nextAllowed() {
		return nextAllowed;
	}

	public Instant silentUntil() {
		return silentUntil;
	}

	public Activity lastActivity() {
		return lastActivity;
	}
}
