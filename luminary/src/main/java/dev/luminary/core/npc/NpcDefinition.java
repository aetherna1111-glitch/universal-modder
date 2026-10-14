package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.character.CharacterSheet;

import java.util.List;
import java.util.Objects;

/**
 * A named, unique NPC. One definition = at most one living entity per world (enforced by {@link NpcRoster}).
 * {@code dialogue} names the {@link DialogueScript} the NPC speaks from; {@code persona} is authored character text
 * (identity, voice, boundaries) given to a model-backed provider; {@code character} is the versioned
 * {@link CharacterSheet} with the authored lines of the deterministic channels; {@code vestments} marks the NPC that
 * wears the bound Vestments of the Envoy. All of it is data, never generated.
 */
public record NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona, List<String> aliases, CharacterSheet character, boolean vestments,
							List<dev.luminary.core.npc.trade.ShopOffer> shop) {
	/** Without the vestments flag: an ordinary NPC that wears whatever it is handed. */
	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona, List<String> aliases, CharacterSheet character) {
		this(id, nameKey, dialogue, persona, aliases, character, false, List.of());
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona, List<String> aliases, CharacterSheet character, boolean vestments) {
		this(id, nameKey, dialogue, persona, aliases, character, vestments, List.of());
	}

	public NpcDefinition {
		shop = List.copyOf(shop);
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(character, "character");
		Objects.requireNonNull(nameKey, "nameKey");
		Objects.requireNonNull(dialogue, "dialogue");
		persona = List.copyOf(persona);
		List<String> cleaned = new java.util.ArrayList<>();
		for (String a : aliases) {
			String t = a == null ? "" : a.strip().toLowerCase(java.util.Locale.ROOT);
			if (!t.isEmpty() && !cleaned.contains(t)) cleaned.add(t);
		}
		if (cleaned.isEmpty()) {
			String path = id.path();
			cleaned.add(path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT));
		}
		aliases = List.copyOf(cleaned);
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona, List<String> aliases) {
		this(id, nameKey, dialogue, persona, aliases, CharacterSheet.EMPTY);
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue, List<String> persona) {
		this(id, nameKey, dialogue, persona, List.of());
	}

	/** Whether a chat line addresses this NPC: it contains one of the aliases (case-insensitive; "Люмин," and "Люмину" both match "люмин"). */
	public boolean isAddressed(String text) {
		if (text == null) {
			return false;
		}
		String t = text.toLowerCase(java.util.Locale.ROOT);
		for (String a : aliases) {
			if (t.contains(a)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The line without the address to this NPC: "Люмин, ты прекрасен" → "ты прекрасен". The aliases (with a trailing
	 * comma, colon or dash and any case ending such as "Люмину") are removed so a small model never mistakes the
	 * player for the character it voices. Empty results fall back to the original.
	 */
	public String stripAddress(String text) {
		if (text == null) {
			return "";
		}
		String out = text;
		for (String a : aliases) {
			out = out.replaceAll("(?iU)\\b" + java.util.regex.Pattern.quote(a) + "[\\p{L}]{0,2}\\b[\\s,:!.\\-]*", " ");
		}
		out = out.replaceAll("\\s{2,}", " ").strip();
		return out.isEmpty() ? text.strip() : out;
	}

	public NpcDefinition(LuminaryId id, String nameKey, LuminaryId dialogue) {
		this(id, nameKey, dialogue, List.of());
	}
}
