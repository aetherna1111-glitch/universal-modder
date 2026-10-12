package dev.luminary.core.progression;

import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.catalog.Result;
import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Civilization progression rules. Pure Java, single-threaded (server thread).
 * <ul>
 *   <li>Technologies unlock automatically when their prerequisites hold ({@link #applyAutoUnlocks}); the service
 *       re-evaluates after every Catalog status change, unlock, era change and signal change.</li>
 *   <li>Unlocking is idempotent and tells the Catalog to mark {@code appliesDiscoveries} as APPLIED: this is the only
 *       path from knowledge to capability.</li>
 *   <li>The civilization enters the next era when that era's prerequisites hold; eras never go backwards.</li>
 *   <li>Administrators may force an unlock; the record says so.</li>
 * </ul>
 */
public final class ProgressionService {
	public static final String REASON_UNKNOWN_TECHNOLOGY = "luminary.progression.unknown_technology";
	public static final String REASON_ALREADY_UNLOCKED = "luminary.progression.already_unlocked";
	public static final String REASON_PREREQUISITES_UNMET = "luminary.progression.prerequisites_unmet";
	public static final String REASON_NO_DEFINITIONS = "luminary.progression.no_definitions";
	public static final String REASON_UNKNOWN_SIGNAL_VALUE = "luminary.progression.bad_signal";

	private final ProgressionState state;
	private final ProgressionDefinitions definitions;
	private final CatalogService catalog;
	private final EventBus events;
	private final Clock clock;
	private final Runnable onChange;
	private boolean evaluating;

	public ProgressionService(ProgressionState state, ProgressionDefinitions definitions, CatalogService catalog, EventBus events,
							  Clock clock, Runnable onChange) {
		this.state = Objects.requireNonNull(state);
		this.definitions = Objects.requireNonNull(definitions);
		this.catalog = Objects.requireNonNull(catalog);
		this.events = Objects.requireNonNull(events);
		this.clock = Objects.requireNonNull(clock);
		this.onChange = Objects.requireNonNull(onChange);
	}

	/** Subscribes to the events that can change prerequisite outcomes and enters the first era if none is set. */
	public void start() {
		events.subscribe(DomainEvent.DiscoveryStatusChanged.class, 100, e -> applyAutoUnlocks());
		events.subscribe(DomainEvent.TechnologyUnlocked.class, 100, e -> applyAutoUnlocks());
		events.subscribe(DomainEvent.EraEntered.class, 100, e -> applyAutoUnlocks());
		events.subscribe(DomainEvent.SignalChanged.class, 100, e -> applyAutoUnlocks());
		ensureFirstEra();
		applyAutoUnlocks();
	}

	public ProgressionState state() {
		return state;
	}

	public ProgressionDefinitions definitions() {
		return definitions;
	}

	// ------------------------------------------------------------------ prerequisites

	public boolean holds(Prerequisite p) {
		if (p instanceof Prerequisite.EraAtLeast e) {
			return eraOrder(state.currentEra().orElse(null)) >= eraOrder(e.era());
		}
		if (p instanceof Prerequisite.DiscoveryVerified d) {
			return catalog.state().get(d.discovery()).map(r -> r.status().isAtLeast(DiscoveryStatus.VERIFIED)).orElse(false);
		}
		if (p instanceof Prerequisite.TechnologyUnlocked t) {
			return state.isUnlocked(t.technology());
		}
		if (p instanceof Prerequisite.Signal s) {
			return state.signal(s.signal()) >= s.minValue();
		}
		throw new IllegalStateException("unknown prerequisite " + p);
	}

	/** Prerequisites of {@code technology} that do not hold yet (empty = unlockable). */
	public List<Prerequisite> missing(Technology technology) {
		List<Prerequisite> out = new ArrayList<>();
		for (Prerequisite p : technology.prerequisites()) {
			if (!holds(p)) {
				out.add(p);
			}
		}
		return out;
	}

	private int eraOrder(LuminaryId era) {
		if (era == null) {
			return Integer.MIN_VALUE;
		}
		return definitions.era(era).map(Era::order).orElse(Integer.MIN_VALUE);
	}

	// ------------------------------------------------------------------ unlocks

	/** Technologies whose prerequisites hold but which are not unlocked yet. */
	public List<Technology> unlockable() {
		List<Technology> out = new ArrayList<>();
		for (Technology t : definitions.technologies()) {
			if (!state.isUnlocked(t.id()) && missing(t).isEmpty()) {
				out.add(t);
			}
		}
		return out;
	}

	/**
	 * Unlocks every unlockable technology and advances eras, repeating until nothing changes. Re-entrant calls (from
	 * the events this method itself publishes) are coalesced into the loop.
	 * @return ids unlocked in this call, in order
	 */
	public List<LuminaryId> applyAutoUnlocks() {
		if (evaluating) {
			return Collections.emptyList();
		}
		evaluating = true;
		List<LuminaryId> unlockedNow = new ArrayList<>();
		try {
			boolean changed = true;
			int guard = 0;
			while (changed && guard++ < 1000) {
				changed = false;
				for (Technology t : unlockable()) {
					doUnlock(t, "prerequisites_met", null);
					unlockedNow.add(t.id());
					changed = true;
				}
				if (advanceEraIfPossible()) {
					changed = true;
				}
			}
		} finally {
			evaluating = false;
		}
		return unlockedNow;
	}

	/**
	 * Explicit unlock (admin command). Without {@code force} prerequisites must hold.
	 */
	public Result unlock(LuminaryId technologyId, UUID actor, boolean force) {
		Optional<Technology> t = definitions.technology(technologyId);
		if (t.isEmpty()) {
			return Result.fail(REASON_UNKNOWN_TECHNOLOGY);
		}
		if (state.isUnlocked(technologyId)) {
			return Result.fail(REASON_ALREADY_UNLOCKED);
		}
		List<Prerequisite> missing = missing(t.get());
		if (!missing.isEmpty() && !force) {
			return Result.fail(REASON_PREREQUISITES_UNMET);
		}
		doUnlock(t.get(), missing.isEmpty() ? "requested" : "admin_override:" + missing.size() + "_unmet", actor);
		applyAutoUnlocks();
		return Result.OK;
	}

	private void doUnlock(Technology t, String reason, UUID actor) {
		Instant now = clock.instant();
		state.putUnlock(t.id(), new ProgressionState.Unlock(now, reason, actor));
		onChange.run();
		for (LuminaryId d : t.appliesDiscoveries()) {
			// Only VERIFIED discoveries can be applied; an unverified one stays as is (forced unlocks may hit this).
			catalog.markApplied(d, t.id());
		}
		events.publish(new DomainEvent.TechnologyUnlocked(now, t.id(), actor));
	}

	/** Capabilities granted by every unlocked technology. */
	public Set<LuminaryId> capabilities() {
		Set<LuminaryId> out = new HashSet<>();
		for (LuminaryId id : state.unlocked().keySet()) {
			definitions.technology(id).ifPresent(t -> out.addAll(t.grantsCapabilities()));
		}
		return out;
	}

	public boolean hasCapability(LuminaryId capability) {
		return capabilities().contains(capability);
	}

	// ------------------------------------------------------------------ eras

	private void ensureFirstEra() {
		if (state.currentEra().isEmpty() || definitions.era(state.currentEra().get()).isEmpty()) {
			definitions.firstEra().ifPresent(first -> {
				Instant now = clock.instant();
				state.enterEra(first.id(), now);
				onChange.run();
				events.publish(new DomainEvent.EraEntered(now, first.id(), null));
			});
		}
	}

	private boolean advanceEraIfPossible() {
		Optional<LuminaryId> current = state.currentEra();
		if (current.isEmpty()) {
			return false;
		}
		Optional<Era> next = definitions.eraAfter(current.get());
		if (next.isEmpty()) {
			return false;
		}
		for (Prerequisite p : next.get().prerequisites()) {
			if (!holds(p)) {
				return false;
			}
		}
		Instant now = clock.instant();
		state.enterEra(next.get().id(), now);
		onChange.run();
		events.publish(new DomainEvent.EraEntered(now, next.get().id(), current.get()));
		return true;
	}

	// ------------------------------------------------------------------ signals

	/** Sets a signal value (integrations, admins). Publishes {@link DomainEvent.SignalChanged} when it changes. */
	public void setSignal(LuminaryId signal, long value, String source) {
		long old = state.signal(signal);
		if (old == value && state.signals().containsKey(signal)) {
			return;
		}
		state.putSignal(signal, value);
		onChange.run();
		events.publish(new DomainEvent.SignalChanged(clock.instant(), signal, old, value, source));
	}
}
