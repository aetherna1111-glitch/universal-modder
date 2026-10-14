package dev.luminary.core.npc.relationship;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Per-NPC map of player → {@link Relationship}. Server thread only; persisted with the roster. */
public final class RelationshipLedger {
	private final Map<UUID, Relationship> relations = new TreeMap<>();

	public Relationship get(UUID player, Instant now, RelationshipRules rules) {
		return relations.getOrDefault(player, Relationship.NEUTRAL).decayed(now, rules);
	}

	public Relationship apply(UUID player, RelationshipEvent event, Instant now, RelationshipRules rules) {
		Relationship r = relations.getOrDefault(player, Relationship.NEUTRAL).apply(event, now, rules);
		relations.put(player, r);
		return r;
	}

	public Relationship.Standing standing(UUID player, Instant now, RelationshipRules rules) {
		return get(player, now, rules).standing(rules);
	}

	public Map<UUID, Relationship> all() {
		return Collections.unmodifiableMap(relations);
	}

	/** Codec only. */
	public void restore(UUID player, Relationship r) {
		relations.put(player, r);
	}
}
