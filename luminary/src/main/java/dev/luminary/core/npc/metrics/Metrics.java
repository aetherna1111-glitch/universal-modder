package dev.luminary.core.npc.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cheap observability: named counters and a few latency summaries (count, total, max in milliseconds). Thread-safe;
 * read by {@code /luminary metrics}. Never persisted.
 */
public final class Metrics {
	public static final String GAZE_REACTIONS = "presence.gaze_reactions";
	public static final String WHISPERS = "presence.whispers";
	public static final String ATTACKS_RECEIVED = "combat.attacks_received";
	public static final String COMBATS = "combat.fights";
	public static final String STRIKES = "combat.strikes";
	public static final String KNOCKOUTS = "combat.knockouts";
	public static final String RECOVERIES = "combat.recoveries";
	public static final String WEAPON_THEFTS = "weapon.thefts";
	public static final String WEAPON_RETURNS = "weapon.returns";
	public static final String WEAPON_PURGED_COPIES = "weapon.purged_copies";
	public static final String PLAN_STEPS_STARTED = "plan.steps_started";
	public static final String PLAN_FAILURES = "plan.failures";
	public static final String ROUTINE_ARRIVALS = "routine.arrivals";
	public static final String MODEL_INTENTS_REFUSED = "capability.model_refused";
	public static final String LLM_DIALOGUE_MS = "llm.dialogue_ms";
	public static final String LLM_INTENT_MS = "llm.intent_ms";

	public record Summary(long count, long totalMillis, long maxMillis) {
		public long meanMillis() {
			return count == 0 ? 0 : totalMillis / count;
		}
	}

	private static final class Lat {
		final LongAdder count = new LongAdder();
		final LongAdder total = new LongAdder();
		final AtomicLong max = new AtomicLong();
	}

	private final Map<String, LongAdder> counters = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, Lat> latencies = new java.util.concurrent.ConcurrentHashMap<>();

	public void inc(String name) {
		counters.computeIfAbsent(name, k -> new LongAdder()).increment();
	}

	public void add(String name, long n) {
		counters.computeIfAbsent(name, k -> new LongAdder()).add(n);
	}

	public long count(String name) {
		LongAdder a = counters.get(name);
		return a == null ? 0 : a.sum();
	}

	public void observeMillis(String name, long millis) {
		Lat l = latencies.computeIfAbsent(name, k -> new Lat());
		l.count.increment();
		l.total.add(millis);
		l.max.accumulateAndGet(millis, Math::max);
	}

	public Summary latency(String name) {
		Lat l = latencies.get(name);
		return l == null ? new Summary(0, 0, 0) : new Summary(l.count.sum(), l.total.sum(), l.max.get());
	}

	public Map<String, Long> counters() {
		Map<String, Long> out = new TreeMap<>();
		counters.forEach((k, v) -> out.put(k, v.sum()));
		return Collections.unmodifiableMap(out);
	}

	public Map<String, Summary> latencies() {
		Map<String, Summary> out = new TreeMap<>();
		latencies.forEach((k, v) -> out.put(k, new Summary(v.count.sum(), v.total.sum(), v.max.get())));
		return Collections.unmodifiableMap(out);
	}
}
