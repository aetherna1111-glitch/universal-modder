package dev.luminary.core.progression;

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

/**
 * Eras and technologies loaded from data packs, replaced wholesale on reload. {@link #validate} checks referential
 * integrity and that the technology-requires-technology graph is acyclic; a known-discovery check is done by the
 * caller against the Catalog templates (see {@link #validate(Set)}).
 */
public final class ProgressionDefinitions {
	private final Map<LuminaryId, Era> eras = new LinkedHashMap<>();
	private final Map<LuminaryId, Technology> technologies = new LinkedHashMap<>();

	public ProgressionDefinitions addEra(Era era) {
		if (eras.putIfAbsent(era.id(), era) != null) {
			throw new IllegalArgumentException("duplicate era " + era.id());
		}
		return this;
	}

	public ProgressionDefinitions addTechnology(Technology t) {
		if (technologies.putIfAbsent(t.id(), t) != null) {
			throw new IllegalArgumentException("duplicate technology " + t.id());
		}
		return this;
	}

	public Optional<Era> era(LuminaryId id) {
		return Optional.ofNullable(eras.get(id));
	}

	public Optional<Technology> technology(LuminaryId id) {
		return Optional.ofNullable(technologies.get(id));
	}

	/** Eras in ascending {@link Era#order()}. */
	public List<Era> erasInOrder() {
		List<Era> list = new ArrayList<>(eras.values());
		list.sort(Comparator.comparingInt(Era::order));
		return Collections.unmodifiableList(list);
	}

	public Collection<Technology> technologies() {
		return Collections.unmodifiableCollection(technologies.values());
	}

	public boolean isEmpty() {
		return eras.isEmpty() && technologies.isEmpty();
	}

	/** Era with the lowest order, if any. */
	public Optional<Era> firstEra() {
		return erasInOrder().stream().findFirst();
	}

	public Optional<Era> eraAfter(LuminaryId current) {
		List<Era> ordered = erasInOrder();
		for (int i = 0; i < ordered.size() - 1; i++) {
			if (ordered.get(i).id().equals(current)) {
				return Optional.of(ordered.get(i + 1));
			}
		}
		return Optional.empty();
	}

	/** Validates references and acyclicity. {@code knownDiscoveries} are the Catalog template ids. */
	public void validate(Set<LuminaryId> knownDiscoveries) {
		Set<Integer> orders = new HashSet<>();
		for (Era e : eras.values()) {
			if (!orders.add(e.order())) {
				throw new IllegalStateException("two eras share order " + e.order());
			}
			checkPrerequisites(e.id(), e.prerequisites(), knownDiscoveries);
		}
		if (!eras.isEmpty() && !firstEra().orElseThrow().prerequisites().isEmpty()) {
			throw new IllegalStateException("the first era " + firstEra().orElseThrow().id() + " must have no prerequisites");
		}
		for (Technology t : technologies.values()) {
			if (!eras.containsKey(t.era())) {
				throw new IllegalStateException(t.id() + " belongs to unknown era " + t.era());
			}
			checkPrerequisites(t.id(), t.prerequisites(), knownDiscoveries);
			for (LuminaryId d : t.appliesDiscoveries()) {
				if (!knownDiscoveries.contains(d)) {
					throw new IllegalStateException(t.id() + " applies unknown discovery " + d);
				}
			}
		}
		checkAcyclic();
	}

	private void checkPrerequisites(LuminaryId owner, Set<Prerequisite> prerequisites, Set<LuminaryId> knownDiscoveries) {
		for (Prerequisite p : prerequisites) {
			if (p instanceof Prerequisite.EraAtLeast e && !eras.containsKey(e.era())) {
				throw new IllegalStateException(owner + " requires unknown era " + e.era());
			}
			if (p instanceof Prerequisite.TechnologyUnlocked t && !technologies.containsKey(t.technology())) {
				throw new IllegalStateException(owner + " requires unknown technology " + t.technology());
			}
			if (p instanceof Prerequisite.DiscoveryVerified d && !knownDiscoveries.contains(d.discovery())) {
				throw new IllegalStateException(owner + " requires unknown discovery " + d.discovery());
			}
		}
	}

	private void checkAcyclic() {
		Map<LuminaryId, Integer> state = new HashMap<>(); // 1 = visiting, 2 = done
		for (LuminaryId id : technologies.keySet()) {
			Deque<LuminaryId> path = new ArrayDeque<>();
			visit(id, state, path);
		}
	}

	private void visit(LuminaryId id, Map<LuminaryId, Integer> state, Deque<LuminaryId> path) {
		Integer s = state.get(id);
		if (s != null && s == 2) {
			return;
		}
		if (s != null && s == 1) {
			throw new IllegalStateException("technology prerequisite cycle: " + path + " -> " + id);
		}
		state.put(id, 1);
		path.addLast(id);
		for (Prerequisite p : technologies.get(id).prerequisites()) {
			if (p instanceof Prerequisite.TechnologyUnlocked t) {
				visit(t.technology(), state, path);
			}
		}
		path.removeLast();
		state.put(id, 2);
	}
}
