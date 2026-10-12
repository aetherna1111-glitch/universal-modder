package dev.luminary.core.npc.memory;

import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.NpcStateCodec;
import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {
	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes()), BOB = UUID.nameUUIDFromBytes("bob".getBytes());
	static final Instant T0 = Instant.parse("2026-10-12T00:00:00Z");

	@Test
	void higherOrEqualTrustSupersedesSameTopicLowerTrustIsRejected() {
		MemoryStore s = new MemoryStore();
		MemoryEntry guess = MemoryEntry.of(MemoryKind.UNVERIFIED_ASSUMPTION, ALICE, "home", "Alice probably lives north", T0, Provenance.MODEL_INFERRED);
		assertEquals(MemoryStore.AddResult.ADDED, s.add(guess, T0));
		MemoryEntry said = MemoryEntry.of(MemoryKind.STABLE_FACT, ALICE, "home", "Alice said she lives by the river", T0.plusSeconds(1), Provenance.PLAYER_SAID);
		assertEquals(MemoryStore.AddResult.SUPERSEDED_OLDER, s.add(said, T0));
		assertEquals(1, s.forPlayer(ALICE).size());
		assertEquals(1, s.superseded().size());
		MemoryEntry laterGuess = MemoryEntry.of(MemoryKind.UNVERIFIED_ASSUMPTION, ALICE, "home", "model thinks: south", T0.plusSeconds(2), Provenance.MODEL_INFERRED);
		assertEquals(MemoryStore.AddResult.REJECTED_LOWER_TRUST, s.add(laterGuess, T0));
		assertEquals("Alice said she lives by the river", s.forPlayer(ALICE).get(0).text());
		// a different player with the same topic is a different fact
		assertEquals(MemoryStore.AddResult.ADDED, s.add(MemoryEntry.of(MemoryKind.STABLE_FACT, BOB, "home", "Bob lives in the tower", T0, Provenance.PLAYER_SAID), T0));
		assertEquals(MemoryStore.AddResult.REJECTED_DUPLICATE, s.add(said, T0));
	}

	@Test
	void capsEvictLowestScoreButNeverPromises() {
		MemoryStore s = new MemoryStore(4, 100);
		MemoryEntry promise = MemoryEntry.of(MemoryKind.PROMISE, ALICE, null, "Promised Alice a map", T0, Provenance.OBSERVED_IN_WORLD);
		s.add(promise, T0);
		for (int i = 0; i < 6; i++) {
			s.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, ALICE, null, "small talk " + i, T0.plus(Duration.ofHours(i)), Provenance.OBSERVED_IN_WORLD), T0.plus(Duration.ofHours(i)));
		}
		List<MemoryEntry> mine = s.forPlayer(ALICE);
		assertEquals(4, mine.size());
		assertTrue(mine.stream().anyMatch(e -> e.id().equals(promise.id())), "promises survive eviction");
		assertTrue(mine.stream().anyMatch(e -> e.text().equals("small talk 5")), "newest small talk kept");
		assertFalse(mine.stream().anyMatch(e -> e.text().equals("small talk 0")), "oldest small talk evicted");
	}

	@Test
	void textIsBoundedAndBlankRejected() {
		MemoryEntry e = MemoryEntry.of(MemoryKind.STABLE_FACT, null, null, "x".repeat(1000), T0, Provenance.AUTHORED);
		assertEquals(MemoryEntry.MAX_TEXT, e.text().length());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> MemoryEntry.of(MemoryKind.STABLE_FACT, null, null, "   ", T0, Provenance.AUTHORED));
	}

	@Test
	void retrieverRanksPromisesPlayerMatchRecencyAndKeywordsWithinBudgets() {
		MemoryStore s = new MemoryStore();
		Instant now = T0.plus(Duration.ofDays(1));
		s.add(MemoryEntry.of(MemoryKind.STABLE_FACT, null, "world/ore", "The first trace ore hums near the river", T0, Provenance.AUTHORED), T0);
		s.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, BOB, null, "Bob said: hello", T0, Provenance.PLAYER_SAID), T0);
		s.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, ALICE, null, "Alice said: where is the ore?", T0.plus(Duration.ofHours(20)), Provenance.PLAYER_SAID), T0);
		MemoryEntry promise = MemoryEntry.of(MemoryKind.PROMISE, ALICE, null, "Promised Alice to show the river path", T0.minus(Duration.ofDays(30)), Provenance.OBSERVED_IN_WORLD);
		s.add(promise, T0);
		s.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, ALICE, null, "Alice said: nice weather", T0.minus(Duration.ofDays(10)), Provenance.PLAYER_SAID), T0);

		List<MemoryEntry> picked = MemoryRetriever.select(s, new MemoryRetriever.Query(ALICE, MemoryRetriever.keywords("tell me about the ore"), 3, 500), now);
		assertEquals(promise.id(), picked.get(0).id(), "an open promise to this player comes first");
		assertTrue(picked.stream().noneMatch(e -> e.text().startsWith("Bob")), "another player's small talk is never selected");
		assertTrue(picked.stream().anyMatch(e -> e.text().contains("hums")), "a shared fact matching the keywords is selected");
		assertEquals(3, picked.size());
		assertEquals(now, s.get(promise.id()).orElseThrow().lastUsed(), "selected entries are touched");

		List<MemoryEntry> tight = MemoryRetriever.select(s, new MemoryRetriever.Query(ALICE, Set.of(), 10, 45), now);
		assertTrue(tight.stream().mapToInt(e -> e.text().length()).sum() <= 45, "character budget respected");
	}

	@Test
	void summarizerCollapsesOldSmallTalkDeterministically() {
		MemoryStore s = new MemoryStore();
		for (int i = 0; i < 15; i++) {
			s.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, ALICE, null, "Alice said: topic" + (i % 3) + " and weather", T0.plus(Duration.ofDays(i)), Provenance.PLAYER_SAID), T0);
		}
		MemorySummarizer sum = MemorySummarizer.defaults();
		Optional<MemoryEntry> summary = sum.compact(s, ALICE, "Alice", T0.plus(Duration.ofDays(20)));
		assertTrue(summary.isPresent());
		assertEquals(MemoryKind.SIGNIFICANT_EVENT, summary.get().kind());
		assertEquals(Provenance.OBSERVED_IN_WORLD, summary.get().provenance());
		assertTrue(summary.get().text().startsWith("Talked with Alice 9 times between 2026-10-12 and 2026-10-20"), summary.get().text());
		assertEquals(6, s.ofKind(MemoryKind.RECENT_INTERACTION).size());
		assertTrue(sum.compact(s, ALICE, "Alice", T0).isEmpty(), "below the threshold nothing happens");
	}

	@Test
	void memoriesRoundTripThroughTheRosterCodec() {
		NpcRoster r = new NpcRoster();
		LuminaryId ai = LuminaryId.of("npc/ai");
		MemoryStore store = r.ensureRecord(ai).memory();
		store.add(MemoryEntry.of(MemoryKind.PROMISE, ALICE, "map", "Promised Alice a map", T0, Provenance.OBSERVED_IN_WORLD), T0);
		store.add(MemoryEntry.of(MemoryKind.STABLE_FACT, null, null, "The archive is east of the square", T0, Provenance.AUTHORED), T0);
		NpcStateCodec codec = NpcStateCodec.production();
		String json = codec.encode(r);
		NpcRoster copy = codec.decode(json);
		assertEquals(json, codec.encode(copy));
		MemoryStore back = copy.get(ai).orElseThrow().memory();
		assertEquals(2, back.size());
		assertEquals("Promised Alice a map", back.forPlayer(ALICE).get(0).text());
		assertEquals(Optional.of("map"), back.forPlayer(ALICE).get(0).topicKey());
	}
}
