package dev.luminary.core.story;

import dev.luminary.core.ids.LuminaryId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Phases, clues and branches from data packs, replaced wholesale on reload. */
public final class StoryDefinitions {
	private final Map<LuminaryId, StoryPhase> phases = new LinkedHashMap<>();
	private final Map<LuminaryId, Clue> clues = new LinkedHashMap<>();
	private final Map<LuminaryId, StoryBranch> branches = new LinkedHashMap<>();

	public StoryDefinitions addPhase(StoryPhase p) {
		if (phases.putIfAbsent(p.id(), p) != null) {
			throw new IllegalArgumentException("duplicate story phase " + p.id());
		}
		return this;
	}

	public StoryDefinitions addClue(Clue c) {
		if (clues.putIfAbsent(c.id(), c) != null) {
			throw new IllegalArgumentException("duplicate clue " + c.id());
		}
		return this;
	}

	public StoryDefinitions addBranch(StoryBranch b) {
		if (branches.putIfAbsent(b.id(), b) != null) {
			throw new IllegalArgumentException("duplicate story branch " + b.id());
		}
		return this;
	}

	public Optional<StoryPhase> phase(LuminaryId id) {
		return Optional.ofNullable(phases.get(id));
	}

	public Optional<Clue> clue(LuminaryId id) {
		return Optional.ofNullable(clues.get(id));
	}

	public Optional<StoryBranch> branch(LuminaryId id) {
		return Optional.ofNullable(branches.get(id));
	}

	public List<StoryPhase> phasesInOrder() {
		List<StoryPhase> list = new ArrayList<>(phases.values());
		list.sort(Comparator.comparingInt(StoryPhase::order));
		return Collections.unmodifiableList(list);
	}

	public Collection<Clue> clues() {
		return Collections.unmodifiableCollection(clues.values());
	}

	public Collection<StoryBranch> branches() {
		return Collections.unmodifiableCollection(branches.values());
	}

	public Optional<StoryPhase> firstPhase() {
		return phasesInOrder().stream().findFirst();
	}

	public Optional<StoryPhase> phaseAfter(LuminaryId current) {
		List<StoryPhase> ordered = phasesInOrder();
		for (int i = 0; i < ordered.size() - 1; i++) {
			if (ordered.get(i).id().equals(current)) {
				return Optional.of(ordered.get(i + 1));
			}
		}
		return Optional.empty();
	}

	/** Checks references and that clue-requires-clue is acyclic. Progression prerequisites are validated by the caller. */
	public void validate() {
		Set<Integer> orders = new HashSet<>();
		for (StoryPhase p : phases.values()) {
			if (!orders.add(p.order())) {
				throw new IllegalStateException("two story phases share order " + p.order());
			}
			checkConditions(p.id(), p.activationConditions());
		}
		if (!phases.isEmpty() && !firstPhase().orElseThrow().activationConditions().isEmpty()) {
			throw new IllegalStateException("the first story phase " + firstPhase().orElseThrow().id() + " must have no activation conditions");
		}
		for (Clue c : clues.values()) {
			if (!phases.containsKey(c.phase())) {
				throw new IllegalStateException("clue " + c.id() + " belongs to unknown phase " + c.phase());
			}
			checkConditions(c.id(), c.conditions());
		}
		Map<LuminaryId, Integer> state = new HashMap<>();
		for (LuminaryId id : clues.keySet()) {
			visit(id, state, new ArrayDeque<>());
		}
	}

	/** Every progression prerequisite referenced by phases and clues, for validation against progression definitions. */
	public List<dev.luminary.core.progression.Prerequisite> progressionPrerequisites() {
		List<dev.luminary.core.progression.Prerequisite> out = new ArrayList<>();
		for (StoryPhase p : phases.values()) {
			collect(p.activationConditions(), out);
		}
		for (Clue c : clues.values()) {
			collect(c.conditions(), out);
		}
		return out;
	}

	private static void collect(Set<StoryCondition> conditions, List<dev.luminary.core.progression.Prerequisite> out) {
		for (StoryCondition c : conditions) {
			if (c instanceof StoryCondition.Progression p) {
				out.add(p.prerequisite());
			}
		}
	}

	private void checkConditions(LuminaryId owner, Set<StoryCondition> conditions) {
		for (StoryCondition c : conditions) {
			if (c instanceof StoryCondition.BranchChosen b && !branches.containsKey(b.branch())) {
				throw new IllegalStateException(owner + " requires unknown branch " + b.branch());
			}
			if (c instanceof StoryCondition.ClueRevealed r && !clues.containsKey(r.clue())) {
				throw new IllegalStateException(owner + " requires unknown clue " + r.clue());
			}
			if (c instanceof StoryCondition.PhaseAtLeast p && !phases.containsKey(p.phase())) {
				throw new IllegalStateException(owner + " requires unknown phase " + p.phase());
			}
		}
	}

	private void visit(LuminaryId id, Map<LuminaryId, Integer> state, Deque<LuminaryId> path) {
		Integer s = state.get(id);
		if (s != null && s == 2) {
			return;
		}
		if (s != null && s == 1) {
			throw new IllegalStateException("clue dependency cycle: " + path + " -> " + id);
		}
		state.put(id, 1);
		path.addLast(id);
		for (StoryCondition c : clues.get(id).conditions()) {
			if (c instanceof StoryCondition.ClueRevealed r) {
				visit(r.clue(), state, path);
			}
		}
		path.removeLast();
		state.put(id, 2);
	}
}
