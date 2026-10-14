package dev.luminary.core.npc.capability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The allowlist between model output and the world. Maps the intent names a model may emit to capabilities,
 * refuses anything unknown or not model-requestable, and counts both so operators can see what the model tried.
 * Pure Java, server thread only.
 */
public final class CapabilityRegistry {
	private static final Map<String, Capability> INTENTS = Map.ofEntries(
			Map.entry("none", Capability.SPEAK),
			Map.entry("gather_wood", Capability.GATHER_WOOD),
			Map.entry("deposit", Capability.DEPOSIT),
			Map.entry("withdraw", Capability.WITHDRAW),
			Map.entry("smelt", Capability.SMELT),
			Map.entry("move_to", Capability.MOVE_TO),
			Map.entry("use_container", Capability.USE_CONTAINER),
			Map.entry("forget_container", Capability.FORGET_CONTAINER));

	private final Map<Capability, Long> granted = new EnumMap<>(Capability.class);
	private final Map<String, Long> refused = new java.util.TreeMap<>();

	/** The intent names a model may use, in a stable order for prompts. */
	public static Set<String> modelIntentNames() {
		return new java.util.TreeSet<>(INTENTS.keySet());
	}

	public static Set<Capability> modelAllowed() {
		EnumSet<Capability> s = EnumSet.noneOf(Capability.class);
		for (Capability c : Capability.values()) {
			if (c.modelMayRequest()) {
				s.add(c);
			}
		}
		return s;
	}

	/**
	 * Resolves a model-emitted intent name. Empty when the name is unknown or maps to a capability the model may
	 * not request; both are counted as refusals.
	 */
	public Optional<Capability> forModelIntent(String intentName) {
		String key = intentName == null ? "" : intentName.strip().toLowerCase(Locale.ROOT);
		Capability c = INTENTS.get(key);
		if (c == null || !c.modelMayRequest()) {
			refused.merge(key.isEmpty() ? "(empty)" : key, 1L, Long::sum);
			return Optional.empty();
		}
		granted.merge(c, 1L, Long::sum);
		return Optional.of(c);
	}

	/** Records that an authoritative (non-model) path exercised a capability; for the audit view. */
	public void exercised(Capability c) {
		granted.merge(c, 1L, Long::sum);
	}

	public Map<Capability, Long> granted() {
		return Collections.unmodifiableMap(granted);
	}

	public Map<String, Long> refused() {
		return Collections.unmodifiableMap(refused);
	}
}
