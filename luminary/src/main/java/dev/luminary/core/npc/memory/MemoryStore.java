package dev.luminary.core.npc.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One NPC's memory. Rules:
 * <ul>
 *   <li>Same topic for the same player: a new entry with equal or higher trust supersedes the old one (the old one is
 *       kept in a short superseded list for debugging); a lower-trust entry is rejected.</li>
 *   <li>Caps: {@code perPlayerCap} entries per player, {@code globalCap} overall; eviction removes the lowest
 *       {@link MemoryRetriever#score} first, never a PROMISE.</li>
 *   <li>Everything is deterministic; no randomness, no wall clock inside (callers pass {@code now}).</li>
 * </ul>
 */
public final class MemoryStore {
	public static final int DEFAULT_PER_PLAYER_CAP = 64;
	public static final int DEFAULT_GLOBAL_CAP = 4096;
	private static final int SUPERSEDED_KEPT = 32;

	public enum AddResult { ADDED, SUPERSEDED_OLDER, REJECTED_LOWER_TRUST, REJECTED_DUPLICATE }

	private final Map<UUID, MemoryEntry> entries = new LinkedHashMap<>();
	private final List<MemoryEntry> superseded = new ArrayList<>();
	private final int perPlayerCap;
	private final int globalCap;

	public MemoryStore() {
		this(DEFAULT_PER_PLAYER_CAP, DEFAULT_GLOBAL_CAP);
	}

	public MemoryStore(int perPlayerCap, int globalCap) {
		this.perPlayerCap = perPlayerCap;
		this.globalCap = globalCap;
	}

	public int size() {
		return entries.size();
	}

	public List<MemoryEntry> all() {
		return List.copyOf(entries.values());
	}

	public List<MemoryEntry> superseded() {
		return Collections.unmodifiableList(superseded);
	}

	public Optional<MemoryEntry> get(UUID id) {
		return Optional.ofNullable(entries.get(id));
	}

	public List<MemoryEntry> forPlayer(UUID player) {
		return entries.values().stream().filter(e -> e.player().map(player::equals).orElse(false)).collect(Collectors.toList());
	}

	public List<MemoryEntry> ofKind(MemoryKind kind) {
		return entries.values().stream().filter(e -> e.kind() == kind).collect(Collectors.toList());
	}

	public AddResult add(MemoryEntry entry, Instant now) {
		if (entries.containsKey(entry.id())) {
			return AddResult.REJECTED_DUPLICATE;
		}
		AddResult result = AddResult.ADDED;
		if (entry.topicKey().isPresent()) {
			for (MemoryEntry existing : new ArrayList<>(entries.values())) {
				if (!existing.sameTopic(entry)) {
					continue;
				}
				if (existing.provenance().trust() > entry.provenance().trust()) {
					return AddResult.REJECTED_LOWER_TRUST;
				}
				entries.remove(existing.id());
				remember(superseded, existing);
				result = AddResult.SUPERSEDED_OLDER;
			}
		}
		entries.put(entry.id(), entry);
		evictIfNeeded(entry.player(), now);
		return result;
	}

	public boolean remove(UUID id) {
		return entries.remove(id) != null;
	}

	/** Replaces a batch of entries by one summary (used by {@link MemorySummarizer}). */
	void replace(List<MemoryEntry> removed, MemoryEntry summary) {
		for (MemoryEntry e : removed) {
			entries.remove(e.id());
		}
		entries.put(summary.id(), summary);
	}

	void touch(MemoryEntry entry, Instant when) {
		if (entries.containsKey(entry.id())) {
			entries.put(entry.id(), entry.withLastUsed(when));
		}
	}

	private void evictIfNeeded(Optional<UUID> player, Instant now) {
		if (player.isPresent()) {
			List<MemoryEntry> mine = forPlayer(player.get());
			evict(mine, mine.size() - perPlayerCap, now);
		}
		if (entries.size() > globalCap) {
			evict(new ArrayList<>(entries.values()), entries.size() - globalCap, now);
		}
	}

	private void evict(List<MemoryEntry> candidates, int count, Instant now) {
		if (count <= 0) {
			return;
		}
		List<MemoryEntry> evictable = candidates.stream().filter(e -> e.kind() != MemoryKind.PROMISE)
				.sorted(Comparator.comparingDouble((MemoryEntry e) -> MemoryRetriever.score(e, null, List.of(), now))
						.thenComparing(MemoryEntry::at))
				.collect(Collectors.toList());
		for (int i = 0; i < Math.min(count, evictable.size()); i++) {
			entries.remove(evictable.get(i).id());
		}
	}

	private static void remember(List<MemoryEntry> list, MemoryEntry e) {
		list.add(e);
		while (list.size() > SUPERSEDED_KEPT) {
			list.remove(0);
		}
	}

	/** Codec only. */
	public static final class Restore {
		private Restore() {
		}

		public static void entry(MemoryStore store, MemoryEntry e) {
			store.entries.put(e.id(), e);
		}
	}
}
