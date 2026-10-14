package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.story.StoryDefinitions;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** NPCs and dialogue scripts from data packs, replaced wholesale on reload. */
public final class NpcDefinitions {
	private final Map<LuminaryId, NpcDefinition> npcs = new LinkedHashMap<>();
	private final Map<LuminaryId, DialogueScript> dialogues = new LinkedHashMap<>();
	private final Map<LuminaryId, dev.luminary.core.npc.trade.Quest> quests = new LinkedHashMap<>();

	public NpcDefinitions addQuest(dev.luminary.core.npc.trade.Quest q) {
		if (quests.putIfAbsent(q.id(), q) != null) {
			throw new IllegalArgumentException("duplicate quest " + q.id());
		}
		return this;
	}

	public Optional<dev.luminary.core.npc.trade.Quest> quest(LuminaryId id) {
		return Optional.ofNullable(quests.get(id));
	}

	/** The quests of one NPC, by {@code order} then id, the special quest last. */
	public java.util.List<dev.luminary.core.npc.trade.Quest> questsOf(LuminaryId npc) {
		java.util.Comparator<dev.luminary.core.npc.trade.Quest> byOrder = java.util.Comparator.comparingInt(dev.luminary.core.npc.trade.Quest::order).thenComparing(q -> q.id().toString());
		java.util.List<dev.luminary.core.npc.trade.Quest> out = new java.util.ArrayList<>();
		quests.values().stream().filter(q -> q.npc().equals(npc) && !q.special()).sorted(byOrder).forEach(out::add);
		quests.values().stream().filter(q -> q.npc().equals(npc) && q.special()).sorted(byOrder).forEach(out::add);
		return out;
	}

	public java.util.Collection<dev.luminary.core.npc.trade.Quest> quests() {
		return java.util.Collections.unmodifiableCollection(quests.values());
	}

	public NpcDefinitions addNpc(NpcDefinition d) {
		if (npcs.putIfAbsent(d.id(), d) != null) {
			throw new IllegalArgumentException("duplicate npc " + d.id());
		}
		return this;
	}

	public NpcDefinitions addDialogue(DialogueScript s) {
		if (dialogues.putIfAbsent(s.id(), s) != null) {
			throw new IllegalArgumentException("duplicate dialogue " + s.id());
		}
		return this;
	}

	public Optional<NpcDefinition> npc(LuminaryId id) {
		return Optional.ofNullable(npcs.get(id));
	}

	public Optional<DialogueScript> dialogue(LuminaryId id) {
		return Optional.ofNullable(dialogues.get(id));
	}

	public Collection<NpcDefinition> npcs() {
		return Collections.unmodifiableCollection(npcs.values());
	}

	public Collection<DialogueScript> dialogues() {
		return Collections.unmodifiableCollection(dialogues.values());
	}

	/** Every NPC's dialogue must exist; every clue a line carries must be a known clue. */
	public void validate(StoryDefinitions story) {
		for (NpcDefinition n : npcs.values()) {
			if (!dialogues.containsKey(n.dialogue())) {
				throw new IllegalStateException("npc " + n.id() + " uses unknown dialogue " + n.dialogue());
			}
		}
		for (DialogueScript s : dialogues.values()) {
			for (DialogueLine l : s.lines()) {
				if (l.clue().isPresent() && story.clue(l.clue().get()).isEmpty()) {
					throw new IllegalStateException("dialogue " + s.id() + " line '" + l.textKey() + "' carries unknown clue " + l.clue().get());
				}
			}
		}
	}

	/** Story conditions used by all lines, for validation of progression references by the caller. */
	public java.util.List<dev.luminary.core.story.StoryCondition> conditions() {
		java.util.List<dev.luminary.core.story.StoryCondition> out = new java.util.ArrayList<>();
		for (DialogueScript s : dialogues.values()) {
			for (DialogueLine l : s.lines()) {
				out.addAll(l.conditions());
			}
		}
		return out;
	}
}
