package dev.luminary.core.catalog;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Permanent credit for the first observation of a discovery. */
public record Attribution(UUID playerId, String playerNameAtTime, Instant at, String dimension, long blockPosPacked) {
	public Attribution {
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(playerNameAtTime, "playerNameAtTime");
		Objects.requireNonNull(at, "at");
		Objects.requireNonNull(dimension, "dimension");
	}
}
