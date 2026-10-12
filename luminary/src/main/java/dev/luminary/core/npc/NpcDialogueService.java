package dev.luminary.core.npc;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.story.StoryController;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates one conversation turn: builds the request, asks the provider, performs the clue reveal through
 * {@link StoryController#reveal} (never bypassing its conditions), records memory and marks state dirty.
 * Pure Java; the Fabric layer only turns the result into chat text.
 */
public final class NpcDialogueService {
	public static final String FALLBACK_TEXT_KEY = "npc.luminary.fallback";

	/** The spoken line, whether it came from the provider, and the clue that was revealed by it (if any). */
	public record Turn(String textKey, boolean fromProvider, Optional<LuminaryId> revealedClue, int interactions) {}

	private final NpcDefinitions definitions;
	private final NpcRoster roster;
	private final StoryController story;
	private final Clock clock;
	private final Runnable onChange;
	private DialogueProvider provider;

	public NpcDialogueService(NpcDefinitions definitions, NpcRoster roster, StoryController story, DialogueProvider provider,
							  Clock clock, Runnable onChange) {
		this.definitions = Objects.requireNonNull(definitions);
		this.roster = Objects.requireNonNull(roster);
		this.story = Objects.requireNonNull(story);
		this.provider = Objects.requireNonNull(provider);
		this.clock = Objects.requireNonNull(clock);
		this.onChange = Objects.requireNonNull(onChange);
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
	};

	/**
	 * @return the turn, or empty when {@code npcId} is unknown (the entity should then say nothing and log).
	 */
	public Optional<Turn> talk(LuminaryId npcId, UUID player, String playerName) {
		Optional<NpcDefinition> def = definitions.npc(npcId);
		if (def.isEmpty()) {
			return Optional.empty();
		}
		Optional<DialogueScript> script = definitions.dialogue(def.get().dialogue());
		Optional<DialogueResponse> response = Optional.empty();
		if (script.isPresent()) {
			DialogueRequest request = new DialogueRequest(npcId, script.get(), player, playerName,
					roster.get(npcId).flatMap(r -> r.player(player)));
			try {
				response = provider.respond(request, gate);
			} catch (RuntimeException e) {
				// a misbehaving provider must never break the interaction; the fallback line is spoken instead
				response = Optional.empty();
			}
		}
		Optional<LuminaryId> revealed = Optional.empty();
		String textKey = FALLBACK_TEXT_KEY;
		if (response.isPresent()) {
			textKey = response.get().textKey();
			if (response.get().clue().isPresent()) {
				LuminaryId clue = response.get().clue().get();
				Result r = story.reveal(clue, "npc:" + npcId);
				if (r.ok()) {
					revealed = Optional.of(clue);
				} else if (!r.reasonKey().equals(StoryController.REASON_ALREADY_REVEALED)) {
					// the provider claimed a clue the story refuses: do not speak it
					textKey = FALLBACK_TEXT_KEY;
				}
			}
		}
		NpcRoster.PlayerMemory memory = roster.remember(npcId, player, playerName, clock.instant(), textKey);
		onChange.run();
		return Optional.of(new Turn(textKey, response.isPresent() && textKey.equals(response.get().textKey()), revealed, memory.interactions()));
	}
}
