package dev.luminary.core.npc;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.conversation.ConversationContext;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.MemoryRetriever;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.memory.MemorySummarizer;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.core.story.Clue;
import dev.luminary.core.story.StoryController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Orchestrates one conversation turn: builds the request (memories, persona), asks the provider, and when the answer
 * arrives (possibly later, from a model thread) finishes ON THE SERVER THREAD via {@code serverExecutor}: performs the
 * clue reveal through {@link StoryController#reveal} (never bypassing its conditions), records memory and marks
 * state dirty. Pure Java; the Fabric layer only turns the result into chat text.
 */
public final class NpcDialogueService {
	public static final String FALLBACK_TEXT_KEY = "npc.luminary.fallback";
	public static final int MAX_UTTERANCE = 200;
	public static final int MEMORY_BUDGET_ENTRIES = 12;
	public static final int MEMORY_BUDGET_CHARS = 1500;

	/** The spoken line (a lang key, or literal text for model lines), whether it came from the provider, the clue revealed, meeting count. */
	public record Turn(String textKey, Optional<String> literalText, boolean fromProvider, Optional<LuminaryId> revealedClue, int interactions) {
		public Turn(String textKey, boolean fromProvider, Optional<LuminaryId> revealedClue, int interactions) {
			this(textKey, Optional.empty(), fromProvider, revealedClue, interactions);
		}
	}

	private final NpcDefinitions definitions;
	private final NpcRoster roster;
	private final StoryController story;
	private final Supplier<Optional<LuminaryId>> currentEra;
	private final Clock clock;
	private final Runnable onChange;
	private final Executor serverExecutor;
	private DialogueProvider provider;
	private final MemorySummarizer summarizer = MemorySummarizer.defaults();

	public NpcDialogueService(NpcDefinitions definitions, NpcRoster roster, StoryController story, Supplier<Optional<LuminaryId>> currentEra,
							  DialogueProvider provider, Clock clock, Runnable onChange, Executor serverExecutor) {
		this.definitions = Objects.requireNonNull(definitions);
		this.roster = Objects.requireNonNull(roster);
		this.story = Objects.requireNonNull(story);
		this.currentEra = Objects.requireNonNull(currentEra);
		this.provider = Objects.requireNonNull(provider);
		this.clock = Objects.requireNonNull(clock);
		this.onChange = Objects.requireNonNull(onChange);
		this.serverExecutor = Objects.requireNonNull(serverExecutor);
	}

	/** Convenience for tests and synchronous providers: direct executor, no era. */
	public NpcDialogueService(NpcDefinitions definitions, NpcRoster roster, StoryController story, DialogueProvider provider, Clock clock, Runnable onChange) {
		this(definitions, roster, story, Optional::empty, provider, clock, onChange, Runnable::run);
	}

	public DialogueProvider provider() {
		return provider;
	}

	public void setProvider(DialogueProvider provider) {
		this.provider = Objects.requireNonNull(provider);
	}

	public NpcDefinitions definitions() {
		return definitions;
	}

	public NpcRoster roster() {
		return roster;
	}

	public MemoryStore memory(LuminaryId npcId) {
		return roster.ensureRecord(npcId).memory();
	}

	private final DialogueProvider.LineGate gate = new DialogueProvider.LineGate() {
		@Override
		public boolean conditionsHold(DialogueLine line) {
			return line.conditions().stream().allMatch(story::holds);
		}

		@Override
		public boolean mayReveal(LuminaryId clue) {
			return story.mayReveal(clue);
		}

		@Override
		public boolean isRevealed(LuminaryId clue) {
			return story.state().isRevealed(clue);
		}

		@Override
		public List<DialogueProvider.RevealableClue> revealableClues() {
			return story.revealable().stream().map(c -> new DialogueProvider.RevealableClue(c.id(), c.textKey())).toList();
		}

		@Override
		public DialogueProvider.PublicContext publicContext() {
			return new DialogueProvider.PublicContext(currentEra.get(), story.state().currentPhase(), story.state().branch(),
					story.revealedClues().stream().map(Clue::id).toList());
		}
	};

	/** Right-click conversation: no utterance. Completes immediately for synchronous providers. */
	public CompletableFuture<Optional<Turn>> talk(LuminaryId npcId, UUID player, String playerName) {
		return talk(npcId, player, playerName, Optional.empty());
	}

	/**
	 * @param utterance what the player said in chat near the NPC, if anything; trimmed to {@link #MAX_UTTERANCE}
	 * @return a future that completes (on the server executor) with the turn, or with empty when {@code npcId} is
	 *         unknown. The future never completes exceptionally: provider failures become the fallback line.
	 */
	public CompletableFuture<Optional<Turn>> talk(LuminaryId npcId, UUID player, String playerName, Optional<String> utterance) {
		return talk(npcId, player, playerName, utterance, List.of());
	}

	/** @param situation what the NPC knows right now, one fact per line, for the model (see {@link DialogueRequest#situation()}) */
	public CompletableFuture<Optional<Turn>> talk(LuminaryId npcId, UUID player, String playerName, Optional<String> utterance, List<String> situation) {
		Optional<NpcDefinition> def = definitions.npc(npcId);
		if (def.isEmpty()) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		Optional<String> said = utterance.map(String::strip).filter(s -> !s.isEmpty())
				.map(s -> s.length() > MAX_UTTERANCE ? s.substring(0, MAX_UTTERANCE) : s);
		MemoryStore store = memory(npcId);
		Instant now = clock.instant();
		Optional<DialogueScript> script = definitions.dialogue(def.get().dialogue());
		if (script.isEmpty()) {
			return CompletableFuture.completedFuture(Optional.of(finish(npcId, player, playerName, said, Optional.empty(), store)));
		}
		List<MemoryEntry> relevant = MemoryRetriever.select(store,
				new MemoryRetriever.Query(player, MemoryRetriever.keywords(said.orElse("")), MEMORY_BUDGET_ENTRIES, MEMORY_BUDGET_CHARS), now);
		NpcRoster.NpcRecord record = roster.ensureRecord(npcId);
		List<ConversationContext.Turn> recent = record.conversation(player).turns(now);
		String standing = record.relationships().standing(player, now, roster.rules().relationship()).name();
		DialogueRequest request = new DialogueRequest(npcId, script.get(), player, playerName,
				Optional.of(record).flatMap(r -> r.player(player)), said, relevant, def.get().persona(), def.get().character().promptLines(), recent, Optional.of(standing), situation);
		CompletableFuture<Optional<DialogueResponse>> answer;
		try {
			answer = provider.respondAsync(request, gate);
		} catch (RuntimeException e) {
			answer = CompletableFuture.completedFuture(Optional.empty());
		}
		CompletableFuture<Optional<Turn>> result = new CompletableFuture<>();
		answer.whenComplete((response, error) -> {
			Optional<DialogueResponse> safe = error == null && response != null ? response : Optional.empty();
			// finish on the server thread: story reveal and memory writes are game state
			serverExecutor.execute(() -> {
				try {
					result.complete(Optional.of(finish(npcId, player, playerName, said, safe, store)));
				} catch (RuntimeException e) {
					result.complete(Optional.of(finish(npcId, player, playerName, said, Optional.empty(), store)));
				}
			});
		});
		return result;
	}

	/** Server-thread half of a turn: reveal, memory, bookkeeping. */
	private Turn finish(LuminaryId npcId, UUID player, String playerName, Optional<String> said, Optional<DialogueResponse> response, MemoryStore store) {
		Instant now = clock.instant();
		Optional<LuminaryId> revealed = Optional.empty();
		String textKey = FALLBACK_TEXT_KEY;
		Optional<String> literal = Optional.empty();
		if (response.isPresent()) {
			textKey = response.get().textKey();
			literal = response.get().literalText();
			if (response.get().clue().isPresent()) {
				LuminaryId clue = response.get().clue().get();
				Result r = story.reveal(clue, "npc:" + npcId);
				if (r.ok()) {
					revealed = Optional.of(clue);
				} else if (!r.reasonKey().equals(StoryController.REASON_ALREADY_REVEALED)) {
					// the provider claimed a clue the story refuses: do not speak it
					textKey = FALLBACK_TEXT_KEY;
					literal = Optional.empty();
				}
			}
			response.get().inferredNote().ifPresent(note ->
					store.add(MemoryEntry.of(MemoryKind.UNVERIFIED_ASSUMPTION, player, null, "About " + playerName + ": " + note, now, Provenance.MODEL_INFERRED), now));
		}
		NpcRoster.PlayerMemory memory = roster.remember(npcId, player, playerName, now, textKey);
		if (said.isPresent()) {
			store.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, player, null, playerName + " said: " + said.get(), now, Provenance.PLAYER_SAID), now);
		} else {
			store.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, player, null, "Met " + playerName + " (meeting " + memory.interactions() + ")", now, Provenance.OBSERVED_IN_WORLD), now);
		}
		if (memory.interactions() == 1) {
			store.add(MemoryEntry.of(MemoryKind.RELATIONSHIP, player, "first_met", "First met " + playerName + " on " + now.toString().substring(0, 10), now, Provenance.OBSERVED_IN_WORLD), now);
		}
		revealed.ifPresent(clue -> store.add(MemoryEntry.of(MemoryKind.DISCOVERY_CLUE, player, "clue/" + clue, "Told " + playerName + " the clue " + clue, now, Provenance.OBSERVED_IN_WORLD), now));
		summarizer.compact(store, player, playerName, now);
		ConversationContext context = roster.ensureRecord(npcId).conversation(player);
		said.ifPresent(t -> context.add(ConversationContext.Role.PLAYER, t, now));
		context.add(ConversationContext.Role.NPC, literal.orElse(textKey), now);
		onChange.run();
		boolean fromProvider = response.isPresent() && textKey.equals(response.get().textKey());
		return new Turn(textKey, literal, fromProvider, revealed, memory.interactions());
	}
}
