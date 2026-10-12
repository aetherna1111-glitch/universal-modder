package dev.luminary.core.npc.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Picks the memories worth putting in front of a dialogue provider for one interaction, within an entry and a
 * character budget. Score = recency (half-life per kind) + importance + player match + keyword overlap, weighted;
 * promises to the current player always rank first. Selected entries get {@code lastUsed} refreshed.
 */
public final class MemoryRetriever {
	public record Query(UUID player, Set<String> keywords, int maxEntries, int maxChars) {
		public Query {
			keywords = keywords.stream().map(k -> k.toLowerCase(Locale.ROOT)).filter(k -> k.length() >= 3).collect(Collectors.toSet());
			if (maxEntries < 1 || maxChars < 1) {
				throw new IllegalArgumentException("budgets must be positive");
			}
		}
	}

	private static final double W_RECENCY = 0.35, W_IMPORTANCE = 0.30, W_PLAYER = 0.20, W_KEYWORDS = 0.15;

	private MemoryRetriever() {
	}

	public static double score(MemoryEntry e, UUID player, Iterable<String> keywords, Instant now) {
		double age = Math.max(0, Duration.between(e.at(), now).toMillis());
		double recency = Math.pow(0.5, age / Math.max(1, e.kind().halfLife().toMillis()));
		double playerMatch = player != null && e.player().map(player::equals).orElse(false) ? 1.0 : e.player().isEmpty() ? 0.5 : 0.0;
		String text = e.text().toLowerCase(Locale.ROOT);
		int hits = 0, total = 0;
		for (String k : keywords) {
			total++;
			if (text.contains(k)) {
				hits++;
			}
		}
		double keywordOverlap = total == 0 ? 0 : (double) hits / total;
		double score = W_RECENCY * recency + W_IMPORTANCE * e.importance() + W_PLAYER * playerMatch + W_KEYWORDS * keywordOverlap;
		if (e.kind() == MemoryKind.PROMISE && playerMatch == 1.0) {
			score += 1.0;
		}
		return score;
	}

	/** Extracts candidate keywords from free text (letters/digits, length ≥ 3, lower-cased). */
	public static Set<String> keywords(String text) {
		if (text == null) {
			return Set.of();
		}
		return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+"))
				.filter(w -> w.length() >= 3).collect(Collectors.toSet());
	}

	public static List<MemoryEntry> select(MemoryStore store, Query q, Instant now) {
		List<MemoryEntry> ranked = store.all().stream()
				.filter(e -> e.player().isEmpty() || e.player().map(q.player()::equals).orElse(false) || e.kind() == MemoryKind.SIGNIFICANT_EVENT)
				.sorted(Comparator.comparingDouble((MemoryEntry e) -> -score(e, q.player(), q.keywords(), now)).thenComparing(MemoryEntry::at, Comparator.reverseOrder()))
				.collect(Collectors.toList());
		List<MemoryEntry> out = new ArrayList<>();
		int chars = 0;
		for (MemoryEntry e : ranked) {
			if (out.size() >= q.maxEntries() || chars + e.text().length() > q.maxChars()) {
				continue;
			}
			out.add(e);
			chars += e.text().length();
		}
		out.forEach(e -> store.touch(e, now));
		return out;
	}
}
