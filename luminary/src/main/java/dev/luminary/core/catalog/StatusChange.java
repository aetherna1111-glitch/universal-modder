package dev.luminary.core.catalog;

import java.time.Instant;
import java.util.UUID;

/** One line of a record's history. {@code actor} is null for system-driven changes. */
public record StatusChange(DiscoveryStatus from, DiscoveryStatus to, Instant at, String reason, UUID actor) {}
