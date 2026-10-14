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

	/** Admin: the player's standing with this NPC starts over (karma, trust and threat at zero). Returns whether there was anything. */
	public boolean reset(UUID player) {
		return relations.remove(player) != null;
	}

	/** Admin: the player's karma set outright; trust and threat kept. */
	public Relationship setKarma(UUID player, double karma, Instant now, RelationshipRules rules) {
		Relationship d = get(player, now, rules);
		Relationship r = new Relationship(Math.max(-100, Math.min(100, karma)), d.trust(), d.threat(), now, d.lastEvent(), d.attacks());
		relations.put(player, r);
		return r;
	}

	/** Codec only. */
	public void restore(UUID player, Relationship r) {
		relations.put(player, r);
	}
}
