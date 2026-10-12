package dev.luminary.core.npc.autonomy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-NPC autonomy switch, standing orders, the decision log that enforces the budget, and targets to avoid after a
 * failure. Persisted with the roster so a restart neither forgets orders nor resets the budget.
 */
public final class AutonomyState {
	private boolean enabled;
	private final List<StandingOrder> orders = new ArrayList<>();
	private final Deque<Instant> decisions = new ArrayDeque<>();
	private final Map<Long, Instant> avoidUntil = new LinkedHashMap<>();

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<StandingOrder> orders() {
		return Collections.unmodifiableList(orders);
	}

	/** Adds an order; an identical order is not duplicated. */
	public boolean addOrder(StandingOrder order) {
		if (orders.contains(order)) {
			return false;
		}
		orders.add(order);
		return true;
	}

	public boolean removeOrder(StandingOrder order) {
		return orders.remove(order);
	}

	public void clearOrders() {
		orders.clear();
	}

	public List<Instant> decisions() {
		return List.copyOf(decisions);
	}

	public Map<Long, Instant> avoidUntil() {
		return Collections.unmodifiableMap(avoidUntil);
	}

	/** Decisions inside the window ending now. */
	public int decisionsWithin(Duration window, Instant now) {
		Instant from = now.minus(window);
		int n = 0;
		for (Instant d : decisions) {
			if (!d.isBefore(from)) {
				n++;
			}
		}
		return n;
	}

	public void recordDecision(Instant now, Duration window) {
		decisions.addLast(now);
		Instant from = now.minus(window);
		while (!decisions.isEmpty() && decisions.peekFirst().isBefore(from)) {
			decisions.pollFirst();
		}
	}

	public void avoid(long pos, Instant until) {
		avoidUntil.put(pos, until);
	}

	public boolean isAvoided(long pos, Instant now) {
		Instant until = avoidUntil.get(pos);
		return until != null && until.isAfter(now);
	}

	/** Drops expired avoidances. */
	public void prune(Instant now) {
		Iterator<Map.Entry<Long, Instant>> it = avoidUntil.entrySet().iterator();
		while (it.hasNext()) {
			if (!it.next().getValue().isAfter(now)) {
				it.remove();
			}
		}
	}

	/** Codec only. */
	public static final class Restore {
		private Restore() {
		}

		public static void decision(AutonomyState s, Instant at) {
			s.decisions.addLast(at);
		}

		public static void avoid(AutonomyState s, long pos, Instant until) {
			s.avoidUntil.put(pos, until);
		}
	}
}
