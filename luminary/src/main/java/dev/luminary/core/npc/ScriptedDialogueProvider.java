package dev.luminary.core.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The default, deterministic provider. A clue line is "news": eligible only while the story allows the reveal and the
 * clue has not been revealed yet (afterwards the clue is on record and the NPC moves on). Among eligible lines the
 * highest priority wins; equal priorities rotate per player based on the last line that player heard.
 */
public final class ScriptedDialogueProvider implements DialogueProvider {
	public static final String ID = "scripted";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Optional<DialogueResponse> respond(DialogueRequest request, LineGate gate) {
		List<DialogueLine> eligible = new ArrayList<>();
		int best = Integer.MIN_VALUE;
		for (DialogueLine line : request.script().lines()) {
			if (!gate.conditionsHold(line)) {
				continue;
			}
			if (line.clue().isPresent() && (gate.isRevealed(line.clue().get()) || !gate.mayReveal(line.clue().get()))) {
				continue;
			}
			if (line.priority() > best) {
				best = line.priority();
				eligible.clear();
			}
			if (line.priority() == best) {
				eligible.add(line);
			}
		}
		if (eligible.isEmpty()) {
			return Optional.empty();
		}
		String last = request.memory().map(NpcRoster.PlayerMemory::lastLineKey).orElse(null);
		int start = 0;
		for (int i = 0; i < eligible.size(); i++) {
			if (eligible.get(i).textKey().equals(last)) {
				start = (i + 1) % eligible.size();
				break;
			}
		}
		DialogueLine chosen = eligible.get(start);
		return Optional.of(new DialogueResponse(chosen.textKey(), chosen.clue(), ID));
	}
}
