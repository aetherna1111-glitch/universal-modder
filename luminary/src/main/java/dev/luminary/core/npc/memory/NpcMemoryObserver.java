package dev.luminary.core.npc.memory;

import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcRoster;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Turns civilization events into NPC memories with provenance OBSERVED_IN_WORLD: every defined NPC "witnesses" what
 * the Catalog, Progression and Story record. This is the only way world facts enter memory, so a dialogue provider
 * can be given true context without ever being the source of it.
 */
public final class NpcMemoryObserver {
	private final Supplier<Collection<NpcDefinition>> npcs;
	private final NpcRoster roster;
	private final Runnable onChange;

	public NpcMemoryObserver(Supplier<Collection<NpcDefinition>> npcs, NpcRoster roster, Runnable onChange) {
		this.npcs = npcs;
		this.roster = roster;
		this.onChange = onChange;
	}

	public void subscribe(EventBus bus) {
		bus.subscribe(DomainEvent.DiscoveryObserved.class, 300, e -> remember(MemoryKind.OBSERVATION, e.firstObserver(), "discovery/" + e.discovery(),
				"Player " + e.firstObserver() + " was the first to observe " + e.discovery(), e.at()));
		bus.subscribe(DomainEvent.DiscoveryStatusChanged.class, 300, e -> remember(MemoryKind.SIGNIFICANT_EVENT, null, "discovery_status/" + e.discovery(),
				"The Catalog now lists " + e.discovery() + " as " + e.to(), e.at()));
		bus.subscribe(DomainEvent.TechnologyUnlocked.class, 300, e -> remember(MemoryKind.SIGNIFICANT_EVENT, null, "technology/" + e.technology(),
				"The civilization unlocked " + e.technology(), e.at()));
		bus.subscribe(DomainEvent.EraEntered.class, 300, e -> remember(MemoryKind.SIGNIFICANT_EVENT, null, "era",
				"The civilization entered the era " + e.era(), e.at()));
		bus.subscribe(DomainEvent.StoryPhaseChanged.class, 300, e -> remember(MemoryKind.SIGNIFICANT_EVENT, null, "story_phase",
				"The story moved to " + e.to(), e.at()));
		bus.subscribe(DomainEvent.StoryBranchChosen.class, 300, e -> remember(MemoryKind.SIGNIFICANT_EVENT, null, "story_branch",
				"The civilization chose the approach " + e.branch(), e.at()));
	}

	private void remember(MemoryKind kind, java.util.UUID player, String topic, String text, java.time.Instant at) {
		boolean changed = false;
		for (NpcDefinition npc : npcs.get()) {
			LuminaryId id = npc.id();
			MemoryStore.AddResult r = roster.ensureRecord(id).memory().add(MemoryEntry.of(kind, player, topic, text, at, Provenance.OBSERVED_IN_WORLD), at);
			changed |= r != MemoryStore.AddResult.REJECTED_DUPLICATE && r != MemoryStore.AddResult.REJECTED_LOWER_TRUST;
		}
		if (changed) {
			onChange.run();
		}
	}
}
