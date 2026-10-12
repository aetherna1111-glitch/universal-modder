package dev.luminary.core.npc;

import java.util.Optional;

/**
 * Fail-safe provider: the NPC never speaks from the script. Used when dialogue is turned off by configuration or when
 * a future external provider is unavailable; the caller shows its fixed fallback line and gameplay continues.
 */
public final class DisabledDialogueProvider implements DialogueProvider {
	public static final String ID = "disabled";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Optional<DialogueResponse> respond(DialogueRequest request, LineGate gate) {
		return Optional.empty();
	}
}
