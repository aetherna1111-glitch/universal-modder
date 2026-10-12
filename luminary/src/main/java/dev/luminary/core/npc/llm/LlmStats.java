package dev.luminary.core.npc.llm;

import java.util.concurrent.atomic.AtomicLong;

/** Counters for status commands and tests. */
public final class LlmStats {
	private final AtomicLong requests = new AtomicLong();
	private final AtomicLong successes = new AtomicLong();
	private final AtomicLong failures = new AtomicLong();
	private final AtomicLong rejectedBusy = new AtomicLong();
	private final AtomicLong rejectedCooldown = new AtomicLong();
	private final AtomicLong totalLatencyMillis = new AtomicLong();

	void request() { requests.incrementAndGet(); }
	void success(long latency) { successes.incrementAndGet(); totalLatencyMillis.addAndGet(latency); }
	void failure() { failures.incrementAndGet(); }
	void busy() { rejectedBusy.incrementAndGet(); }
	void cooldown() { rejectedCooldown.incrementAndGet(); }

	public long requests() { return requests.get(); }
	public long successes() { return successes.get(); }
	public long failures() { return failures.get(); }
	public long rejectedBusy() { return rejectedBusy.get(); }
	public long rejectedCooldown() { return rejectedCooldown.get(); }

	public long averageLatencyMillis() {
		long s = successes.get();
		return s == 0 ? 0 : totalLatencyMillis.get() / s;
	}

	@Override
	public String toString() {
		return "requests=" + requests() + " ok=" + successes() + " failed=" + failures() + " busy=" + rejectedBusy()
				+ " cooldown=" + rejectedCooldown() + " avgLatencyMs=" + averageLatencyMillis();
	}
}
