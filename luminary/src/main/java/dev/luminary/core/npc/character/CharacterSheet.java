package dev.luminary.core.npc.character;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The authored, versioned identity of an NPC: who they are, how they speak, what they never do, and the short
 * authored lines used by deterministic channels (gaze reactions, whispers, warnings) that must never cost a model
 * call. Loaded from data (the {@code character} object of an NPC definition); the code never generates lore.
 * <p>{@code version} lets the lore evolve without silently rewriting the character: a changed sheet is logged.
 * Lines are grouped by key and by language ({@code "en"}, {@code "ru"}); {@link #line} falls back to English.</p>
 */
public record CharacterSheet(int version, String origin, List<String> principles, List<String> speechStyle, List<String> boundaries,
							 List<String> loreFacts, Map<String, Map<String, List<String>>> lines) {
	public static final String LANG_DEFAULT = "en";
	public static final CharacterSheet EMPTY = new CharacterSheet(0, "", List.of(), List.of(), List.of(), List.of(), Map.of());

	/** Line groups used by the deterministic channels. */
	public static final String LINES_GAZE = "gaze";
	public static final String LINES_GAZE_FRIEND = "gaze_friend";
	public static final String LINES_GAZE_HOSTILE = "gaze_hostile";
	public static final String LINES_MUTTER_PREFIX = "mutter.";
	public static final String LINES_WARNING = "warning";
	public static final String LINES_DEESCALATE = "deescalate";
	public static final String LINES_FIGHT = "fight";
	public static final String LINES_KNOCKED_OUT = "knocked_out";
	public static final String LINES_RECOVERED = "recovered";
	public static final String LINES_WEAPON_TAKEN = "weapon_taken";
	public static final String LINES_WEAPON_RETURNED = "weapon_returned";
	public static final String LINES_APOLOGY_ACCEPTED = "apology_accepted";
	public static final String LINES_HELPED = "helped";
	public static final String LINES_DUEL_ACCEPTED = "duel_accepted";

	public CharacterSheet {
		Objects.requireNonNull(origin, "origin");
		principles = List.copyOf(principles);
		speechStyle = List.copyOf(speechStyle);
		boundaries = List.copyOf(boundaries);
		loreFacts = List.copyOf(loreFacts);
		Map<String, Map<String, List<String>>> copy = new LinkedHashMap<>();
		lines.forEach((key, byLang) -> {
			Map<String, List<String>> l = new LinkedHashMap<>();
			byLang.forEach((lang, list) -> l.put(lang.toLowerCase(Locale.ROOT), List.copyOf(list)));
			copy.put(key, Collections.unmodifiableMap(l));
		});
		lines = Collections.unmodifiableMap(copy);
	}

	public boolean isEmpty() {
		return version == 0 && origin.isEmpty() && principles.isEmpty() && lines.isEmpty();
	}

	/** All lines of a group in {@code lang}, else in English, else empty. */
	public List<String> lines(String key, String lang) {
		Map<String, List<String>> byLang = lines.get(key);
		if (byLang == null) {
			return List.of();
		}
		List<String> l = byLang.get(lang == null ? LANG_DEFAULT : lang.toLowerCase(Locale.ROOT));
		if (l == null || l.isEmpty()) {
			l = byLang.get(LANG_DEFAULT);
		}
		return l == null ? List.of() : l;
	}

	/** Deterministic pick: the {@code seed}-th line of the group (wrapping), so rotation is reproducible in tests. */
	public Optional<String> line(String key, String lang, long seed) {
		List<String> l = lines(key, lang);
		if (l.isEmpty()) {
			return Optional.empty();
		}
		int i = (int) Math.floorMod(seed, (long) l.size());
		return Optional.of(l.get(i));
	}

	/** The identity text handed to a model provider, after the persona. Authored data only. */
	public List<String> promptLines() {
		List<String> out = new java.util.ArrayList<>();
		if (!origin.isEmpty()) {
			out.add("Origin: " + origin);
		}
		for (String p : principles) {
			out.add("Principle: " + p);
		}
		for (String s : speechStyle) {
			out.add("Voice: " + s);
		}
		for (String b : boundaries) {
			out.add("Never: " + b);
		}
		for (String f : loreFacts) {
			out.add("Known: " + f);
		}
		return out;
	}
}
