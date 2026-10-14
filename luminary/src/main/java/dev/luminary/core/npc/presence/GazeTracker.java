package dev.luminary.core.npc.presence;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Player-initiated engagement, deterministic and cheap: when a player looks at the NPC, the NPC may acknowledge
 * them with one short authored line, at most once per {@code perPlayer} cooldown for that player and once per
 * {@code global} cooldown overall. Never asks a model; never fires for a glance from someone it just answered.
 * Pure Java with an injected clock value; the Fabric layer supplies the gaze test and the chat.
 */
public final class GazeTracker {
	public record Rules(Duration perPlayer, Duration global, Duration minimumGaze) {
		public static final Rules DEFAULTS = new Rules(Duration.ofSeconds(45), Duration.ofSeconds(10), Duration.ofMillis(600));

		public Rules {
			if (perPlayer.isNegative() || global.isNegative() || minimumGaze.isNegative()) {
				throw new IllegalArgumentException("gaze cooldowns must not be negative");
			}
		}
	}

	/** A reaction the body should perform: the player to face and the sequence number (for deterministic line rotation). */
	public record Reaction(UUID player, long sequence) {
	}

	private final Rules rules;
	private final Map<UUID, Instant> lastReaction = new HashMap<>();
	private final Map<UUID, Instant> gazeStarted = new HashMap<>();
	private Instant lastAny = Instant.EPOCH;
	private long sequence;

	public GazeTracker(Rules rules) {
		this.rules = rules;
	}

	public Rules rules() {
		return rules;
	}

	/**
	 * Called every tick a player is looking at the NPC. Returns a reaction once the gaze has lasted
	 * {@code minimumGaze} and both cooldowns have passed.
	 */
	public Optional<Reaction> onGazing(UUID player, Instant now) {
		Instant started = gazeStarted.computeIfAbsent(player, p -> now);
		if (Duration.between(started, now).compareTo(rules.minimumGaze()) < 0) {
			return Optional.empty();
		}
		Instant last = lastReaction.get(player);
		if (last != null && Duration.between(last, now).compareTo(rules.perPlayer()) < 0) {
			return Optional.empty();
		}
		if (Duration.between(lastAny, now).compareTo(rules.global()) < 0) {
			return Optional.empty();
		}
		lastReaction.put(player, now);
		lastAny = now;
		return Optional.of(new Reaction(player, sequence++));
	}

	/** The player looked away: the gaze timer restarts, cooldowns are untouched. */
	public void onLookedAway(UUID player) {
		gazeStarted.remove(player);
	}

	/** A conversation with this player counts as acknowledgement: no gaze line right after talking. */
	public void onSpoke(UUID player, Instant now) {
		lastReaction.put(player, now);
	}

	/** Drops stale per-player entries (players gone for longer than the cooldown). */
	public void prune(Instant now) {
		lastReaction.entrySet().removeIf(e -> Duration.between(e.getValue(), now).compareTo(rules.perPlayer().multipliedBy(4)) > 0);
	}
}
