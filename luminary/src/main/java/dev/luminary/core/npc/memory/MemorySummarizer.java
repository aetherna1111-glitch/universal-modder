package dev.luminary.core.npc.memory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic compaction: when a player's RECENT_INTERACTION entries exceed {@code threshold}, the oldest ones
 * (all but {@code keep}) collapse into one SIGNIFICANT_EVENT with provenance OBSERVED_IN_WORLD (counts and dates are
 * facts; no model is involved, so the summary is reproducible and never invents content).
 */
public final class MemorySummarizer {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

	private final int threshold;
	private final int keep;

	public MemorySummarizer(int threshold, int keep) {
		if (keep >= threshold || keep < 1) {
			throw new IllegalArgumentException("keep must be >= 1 and < threshold");
		}
		this.threshold = threshold;
		this.keep = keep;
	}

	public static MemorySummarizer defaults() {
		return new MemorySummarizer(12, 6);
	}

	/** @return the summary entry if a compaction happened. */
	public Optional<MemoryEntry> compact(MemoryStore store, UUID player, String playerName, Instant now) {
		List<MemoryEntry> recent = store.forPlayer(player).stream()
				.filter(e -> e.kind() == MemoryKind.RECENT_INTERACTION)
				.sorted(Comparator.comparing(MemoryEntry::at))
				.collect(Collectors.toList());
		if (recent.size() <= threshold) {
			return Optional.empty();
		}
		List<MemoryEntry> old = recent.subList(0, recent.size() - keep);
		String first = DAY.format(old.get(0).at());
		String last = DAY.format(old.get(old.size() - 1).at());
		String topics = old.stream().flatMap(e -> MemoryRetriever.keywords(e.text()).stream()).distinct().sorted().limit(8).collect(Collectors.joining(", "));
		String text = "Talked with " + playerName + " " + old.size() + " times between " + first + " and " + last
				+ (topics.isEmpty() ? "" : "; topics: " + topics) + ".";
		MemoryEntry summary = MemoryEntry.of(MemoryKind.SIGNIFICANT_EVENT, player, "summary/" + first + "/" + last, text, now, Provenance.OBSERVED_IN_WORLD);
		store.replace(List.copyOf(old), summary);
		return Optional.of(summary);
	}
}
