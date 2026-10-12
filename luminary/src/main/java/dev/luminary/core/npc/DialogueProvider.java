package dev.luminary.core.npc;

import java.util.Optional;

/**
 * Chooses what an NPC says. Implementations must be synchronous and side-effect free: they may read the request and
 * the story gate, and return at most one line. A future model-backed provider must still return only a line from the
 * script (or a free-text line without {@code clue}): it never gets a path to story or progression mutation.
 * <p>Returning {@link Optional#empty()} means "nothing to say"; the caller falls back to a fixed line and the game goes on.</p>
 */
public interface DialogueProvider {
	/** Stable id for logs and the admin command (e.g. {@code scripted}, {@code disabled}). */
	String id();

	/**
	 * @param request   the interaction snapshot
	 * @param gate      evaluates line conditions and clue availability against authoritative story/progression state
	 */
	Optional<DialogueResponse> respond(DialogueRequest request, LineGate gate);

	/** Read-only view of the story/progression state a provider may consult. */
	interface LineGate {
		boolean conditionsHold(DialogueLine line);

		boolean mayReveal(dev.luminary.core.ids.LuminaryId clue);

		boolean isRevealed(dev.luminary.core.ids.LuminaryId clue);
	}
}
