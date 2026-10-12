package dev.luminary.core.catalog;

import dev.luminary.core.events.DomainEvent;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.ids.LuminaryId;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rules of the Catalog of the Unknown. Pure Java; all calls are expected on one thread (the server thread).
 * <ul>
 *   <li>{@link #observe}: first observer is attributed forever; later observers become evidence.</li>
 *   <li>{@link #addEvidence}: idempotent on evidence id.</li>
 *   <li>{@link #tryVerify}: requirements and dependencies must hold; an admin may override, and the override is recorded.</li>
 *   <li>{@link #markApplied}: only Progression calls this; the Catalog never decides application itself.</li>
 * </ul>
 */
public final class CatalogService {
	public static final String REASON_UNKNOWN_TEMPLATE = "luminary.catalog.unknown_template";
	public static final String REASON_NOT_OBSERVED = "luminary.catalog.not_observed";
	public static final String REASON_ALREADY_VERIFIED = "luminary.catalog.already_verified";
	public static final String REASON_NOT_VERIFIED = "luminary.catalog.not_verified";
	public static final String REASON_ALREADY_APPLIED = "luminary.catalog.already_applied";
	public static final String REASON_DEPENDENCY_UNVERIFIED = "luminary.catalog.dependency_unverified";
	public static final String REASON_NEED_CONTRIBUTORS = "luminary.catalog.need_contributors";
	public static final String REASON_NEED_SAMPLES = "luminary.catalog.need_samples";
	public static final String REASON_NEED_EXPERIMENT = "luminary.catalog.need_experiment";
	public static final String REASON_DUPLICATE_EVIDENCE = "luminary.catalog.duplicate_evidence";

	private final CatalogState state;
	private final Templates templates;
	private final EventBus events;
	private final Clock clock;
	private final Runnable onChange;

	/**
	 * @param onChange invoked after every successful mutation (production: {@code PersistentState.markDirty()}).
	 */
	public CatalogService(CatalogState state, Templates templates, EventBus events, Clock clock, Runnable onChange) {
		this.state = Objects.requireNonNull(state);
		this.templates = Objects.requireNonNull(templates);
		this.events = Objects.requireNonNull(events);
		this.clock = Objects.requireNonNull(clock);
		this.onChange = Objects.requireNonNull(onChange);
	}

	public CatalogState state() {
		return state;
	}

	public Templates templates() {
		return templates;
	}

	/** Records an observation. The first call for an id creates the record with permanent attribution. */
	public Result observe(LuminaryId templateId, Attribution who) {
		if (templates.get(templateId).isEmpty()) {
			return Result.fail(REASON_UNKNOWN_TEMPLATE);
		}
		Optional<DiscoveryRecord> existing = state.get(templateId);
		if (existing.isEmpty()) {
			Instant now = clock.instant();
			DiscoveryRecord record = new DiscoveryRecord(templateId, DiscoveryStatus.OBSERVED, who, java.util.List.of(),
					java.util.List.of(new StatusChange(null, DiscoveryStatus.OBSERVED, now, "first_observation", who.playerId())));
			state.put(record);
			onChange.run();
			events.publish(new DomainEvent.DiscoveryObserved(now, templateId, who.playerId()));
			return Result.OK;
		}
		// Later observers: evidence, deterministic id so a repeated report of the same observer is a no-op.
		LuminaryId evidenceId = new LuminaryId(templateId.namespace(), templateId.path() + "/observation/" + who.playerId());
		return addEvidence(templateId, new Evidence.Observation(evidenceId, who.playerId(), clock.instant(), "re-observed"));
	}

	public Result addEvidence(LuminaryId templateId, Evidence evidence) {
		Optional<DiscoveryRecord> existing = state.get(templateId);
		if (existing.isEmpty()) {
			return Result.fail(REASON_NOT_OBSERVED);
		}
		DiscoveryRecord record = existing.get();
		if (record.evidence().stream().anyMatch(e -> e.id().equals(evidence.id()))) {
			return Result.fail(REASON_DUPLICATE_EVIDENCE);
		}
		state.put(record.withEvidence(evidence));
		onChange.run();
		events.publish(new DomainEvent.EvidenceAdded(clock.instant(), templateId, evidence.id(), evidence.contributor()));
		return Result.OK;
	}

	/** Checks requirements without mutating; useful for UI ("what is still missing?"). */
	public Result checkVerifiable(LuminaryId templateId) {
		Optional<DiscoveryTemplate> t = templates.get(templateId);
		if (t.isEmpty()) {
			return Result.fail(REASON_UNKNOWN_TEMPLATE);
		}
		Optional<DiscoveryRecord> r = state.get(templateId);
		if (r.isEmpty()) {
			return Result.fail(REASON_NOT_OBSERVED);
		}
		DiscoveryRecord record = r.get();
		if (record.status().isAtLeast(DiscoveryStatus.VERIFIED)) {
			return Result.fail(REASON_ALREADY_VERIFIED);
		}
		for (LuminaryId dep : t.get().dependsOn()) {
			if (state.get(dep).map(d -> !d.status().isAtLeast(DiscoveryStatus.VERIFIED)).orElse(true)) {
				return Result.fail(REASON_DEPENDENCY_UNVERIFIED);
			}
		}
		VerificationRequirement req = t.get().verification();
		Set<UUID> contributors = new HashSet<>();
		contributors.add(record.firstObserver().playerId());
		int samples = 0;
		Set<LuminaryId> experiments = new HashSet<>();
		for (Evidence e : record.evidence()) {
			contributors.add(e.contributor());
			if (e instanceof Evidence.Sample s) {
				samples += s.count();
			} else if (e instanceof Evidence.Experiment x) {
				experiments.add(x.experimentId());
			}
		}
		if (contributors.size() < req.minDistinctContributors()) {
			return Result.fail(REASON_NEED_CONTRIBUTORS);
		}
		if (samples < req.minSamples()) {
			return Result.fail(REASON_NEED_SAMPLES);
		}
		if (!experiments.containsAll(req.requiredExperiments())) {
			return Result.fail(REASON_NEED_EXPERIMENT);
		}
		for (LuminaryId dep : req.requiredVerifiedDiscoveries()) {
			if (state.get(dep).map(d -> !d.status().isAtLeast(DiscoveryStatus.VERIFIED)).orElse(true)) {
				return Result.fail(REASON_DEPENDENCY_UNVERIFIED);
			}
		}
		return Result.OK;
	}

	/**
	 * Moves OBSERVED → VERIFIED when {@link #checkVerifiable} passes.
	 * @param adminOverride when true, requirement failures (not "unknown"/"not observed"/"already") are bypassed and
	 *                      the history line says so.
	 */
	public Result tryVerify(LuminaryId templateId, UUID actor, boolean adminOverride) {
		Result check = checkVerifiable(templateId);
		boolean structural = check.reasonKey().equals(REASON_UNKNOWN_TEMPLATE) || check.reasonKey().equals(REASON_NOT_OBSERVED)
				|| check.reasonKey().equals(REASON_ALREADY_VERIFIED);
		if (!check.ok() && (structural || !adminOverride)) {
			return check;
		}
		String reason = check.ok() ? "requirements_met" : "admin_override:" + check.reasonKey();
		transition(templateId, DiscoveryStatus.VERIFIED, reason, actor);
		return Result.OK;
	}

	/** VERIFIED → APPLIED. Intended caller: Progression, when a technology consuming this discovery unlocks. */
	public Result markApplied(LuminaryId templateId, LuminaryId byTechnology) {
		Optional<DiscoveryRecord> r = state.get(templateId);
		if (r.isEmpty()) {
			return Result.fail(REASON_NOT_OBSERVED);
		}
		if (r.get().status() == DiscoveryStatus.APPLIED) {
			return Result.fail(REASON_ALREADY_APPLIED);
		}
		if (r.get().status() != DiscoveryStatus.VERIFIED) {
			return Result.fail(REASON_NOT_VERIFIED);
		}
		transition(templateId, DiscoveryStatus.APPLIED, "technology:" + byTechnology, null);
		return Result.OK;
	}

	private void transition(LuminaryId id, DiscoveryStatus to, String reason, UUID actor) {
		DiscoveryRecord record = state.get(id).orElseThrow();
		Instant now = clock.instant();
		DiscoveryStatus from = record.status();
		state.put(record.withStatus(to, new StatusChange(from, to, now, reason, actor)));
		onChange.run();
		events.publish(new DomainEvent.DiscoveryStatusChanged(now, id, from, to, actor));
	}
}
