package dev.luminary.core.npc.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One remembered thing. {@code topicKey} groups entries that describe the same fact (e.g. {@code home}, {@code
 * favourite_ore}) so a newer, better-sourced entry can supersede an older one. Text is bounded so a chatty player
 * cannot fill the store.
 */
public record MemoryEntry(UUID id, MemoryKind kind, Optional<UUID> player, Optional<String> topicKey, String text, Instant at,
						  Instant lastUsed, double importance, Provenance provenance) {
	public static final int MAX_TEXT = 240;

	public MemoryEntry {
		Objects.requireNonNull(id);
		Objects.requireNonNull(kind);
		Objects.requireNonNull(player);
		Objects.requireNonNull(topicKey);
		Objects.requireNonNull(text);
		Objects.requireNonNull(at);
		Objects.requireNonNull(lastUsed);
		Objects.requireNonNull(provenance);
		if (text.isBlank()) {
			throw new IllegalArgumentException("memory text must not be blank");
		}
		if (text.length() > MAX_TEXT) {
			text = text.substring(0, MAX_TEXT - 1) + "…";
		}
		if (importance < 0 || importance > 1) {
			throw new IllegalArgumentException("importance must be within 0..1");
		}
		topicKey.ifPresent(k -> {
			if (k.isBlank()) {
				throw new IllegalArgumentException("topicKey must not be blank");
			}
		});
	}

	public static MemoryEntry of(MemoryKind kind, UUID player, String topicKey, String text, Instant at, Provenance provenance) {
		return new MemoryEntry(UUID.randomUUID(), kind, Optional.ofNullable(player), Optional.ofNullable(topicKey), text, at, at,
				kind.defaultImportance(), provenance);
	}

	public MemoryEntry withLastUsed(Instant when) {
		return new MemoryEntry(id, kind, player, topicKey, text, at, when, importance, provenance);
	}

	public MemoryEntry withImportance(double value) {
		return new MemoryEntry(id, kind, player, topicKey, text, at, lastUsed, value, provenance);
	}

	boolean sameTopic(MemoryEntry other) {
		return topicKey.isPresent() && topicKey.equals(other.topicKey) && player.equals(other.player);
	}
}
