package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Chooses what an NPC says. Implementations may read the request and the gate and return at most one line; they
 * never receive a path to story, progression or world mutation. The synchronous {@link #respond} is for deterministic
 * providers; {@link #respondAsync} is the general form (a model-backed provider completes later on its own thread,
 * the caller hops back to the server thread). Returning empty means "nothing to say": the caller falls back.
 */
public interface DialogueProvider {
	String id();

	Optional<DialogueResponse> respond(DialogueRequest request, LineGate gate);

	default CompletableFuture<Optional<DialogueResponse>> respondAsync(DialogueRequest request, LineGate gate) {
		try {
			return CompletableFuture.completedFuture(respond(request, gate));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Read-only view of the story/progression state a provider may consult. */
	interface LineGate {
		boolean conditionsHold(DialogueLine line);

		boolean mayReveal(LuminaryId clue);

		boolean isRevealed(LuminaryId clue);

		/** Clues the story permits revealing right now and that are not revealed yet, with their authored text. */
		List<RevealableClue> revealableClues();

		/** Public context a provider may describe: era, story phase, branch (ids; never restricted content). */
		PublicContext publicContext();
	}

	record RevealableClue(LuminaryId id, String text) {}

	record PublicContext(Optional<LuminaryId> era, Optional<LuminaryId> storyPhase, Optional<LuminaryId> branch, List<LuminaryId> revealedClues) {}
}
