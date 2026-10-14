package dev.luminary.core.npc.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The NPC at zero health: not dead, down for a fixed real-time span (UTC instants, so the deadline survives
 * restarts, logouts and unloaded chunks; nothing counts game ticks). Help shortens the deadline; loot is noted.
 * The body stays the same entity: no respawn, no duplicate.
 */
public record Knockout(Instant since, Instant deadline, Optional<UUID> downedBy, int timesHelped, int timesLooted) {
	public Knockout {
		Objects.requireNonNull(since);
		Objects.requireNonNull(deadline);
		Objects.requireNonNull(downedBy);
	}

	public static Knockout start(Instant now, Duration length, UUID by) {
		return new Knockout(now, now.plus(length), Optional.ofNullable(by), 0, 0);
	}

	public boolean over(Instant now) {
		return !now.isBefore(deadline);
	}

	public Duration remaining(Instant now) {
		Duration d = Duration.between(now, deadline);
		return d.isNegative() ? Duration.ZERO : d;
	}

	/** A player fed or treated the NPC: the deadline moves closer by {@code by}, never before {@code now}. */
	public Knockout helped(Instant now, Duration by) {
		Instant d = deadline.minus(by);
		return new Knockout(since, d.isBefore(now) ? now : d, downedBy, timesHelped + 1, timesLooted);
	}

	public Knockout looted() {
		return new Knockout(since, deadline, downedBy, timesHelped, timesLooted + 1);
	}
}
