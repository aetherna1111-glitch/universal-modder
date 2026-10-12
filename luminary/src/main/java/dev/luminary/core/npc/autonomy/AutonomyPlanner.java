package dev.luminary.core.npc.autonomy;

import dev.luminary.core.npc.intent.IntentCandidates;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.TaskRules;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic self-direction: reflexes first (carrying things while idle → put them away), then standing orders
 * in the order they were given. The planner only proposes; the result goes through {@code NpcService.assign} and the
 * full validator chain like any command, counts against the budget, and a refused or failed target is avoided for a
 * while so the NPC never loops on an impossible goal. No model is involved: autonomy must be predictable.
 */
public final class AutonomyPlanner {
	public static final String REASON_BUSY = "busy";
	public static final String REASON_DISABLED = "disabled";
	public static final String REASON_BUDGET = "budget_exhausted";
	public static final String REASON_NOTHING = "nothing_to_do";
	public static final String REASON_REFLEX_EMPTY_HANDS = "reflex:empty_hands";
	public static final String REASON_ORDER_STOCK_WOOD = "order:stock_wood";

	/** What the planner proposes and why; {@code action} empty means stay idle. */
	public record Decision(Optional<NpcAction> action, String reason) {
		static Decision idle(String reason) {
			return new Decision(Optional.empty(), reason);
		}
	}

	private final AutonomyRules rules;
	private final TaskRules taskRules;

	public AutonomyPlanner(AutonomyRules rules, TaskRules taskRules) {
		this.rules = rules;
		this.taskRules = taskRules;
	}

	public AutonomyRules rules() {
		return rules;
	}

	public Decision decide(AutonomyState state, IntentCandidates seen, boolean busy, Instant now) {
		if (!state.enabled()) {
			return Decision.idle(REASON_DISABLED);
		}
		if (busy) {
			return Decision.idle(REASON_BUSY);
		}
		if (state.decisionsWithin(rules.window(), now) >= rules.maxDecisionsPerWindow()) {
			return Decision.idle(REASON_BUDGET);
		}
		// reflex: idle with full hands → deposit into the nearest usable container
		if (seen.heldTotal() > 0) {
			Optional<IntentCandidates.ContainerInfo> target = seen.containers().stream().filter(c -> !state.isAvoided(c.spot().pos(), now)).findFirst();
			if (target.isPresent()) {
				return new Decision(Optional.of(new NpcAction.Deposit(target.get().spot().pos())), REASON_REFLEX_EMPTY_HANDS);
			}
		}
		for (StandingOrder order : state.orders()) {
			if (order instanceof StandingOrder.StockWood stock) {
				Optional<Decision> d = stockWood(stock, state, seen, now);
				if (d.isPresent()) {
					return d.get();
				}
			}
		}
		return Decision.idle(REASON_NOTHING);
	}

	private Optional<Decision> stockWood(StandingOrder.StockWood order, AutonomyState state, IntentCandidates seen, Instant now) {
		if (state.isAvoided(order.containerPos(), now)) {
			return Optional.empty();
		}
		Optional<IntentCandidates.ContainerInfo> container = seen.containers().stream().filter(c -> c.spot().pos() == order.containerPos()).findFirst();
		if (container.isEmpty()) {
			return Optional.empty();   // not loaded, not authorized any more, or out of range: nothing to do
		}
		int have = logsIn(container.get().contents());
		if (have >= order.minLogs()) {
			return Optional.empty();
		}
		Optional<IntentCandidates.Spot> log = seen.logs().stream().filter(l -> !state.isAvoided(l.pos(), now)).findFirst();
		if (log.isEmpty()) {
			return Optional.empty();
		}
		int need = Math.max(1, Math.min(order.minLogs() - have, taskRules.maxLogsPerTask()));
		return Optional.of(new Decision(Optional.of(new NpcAction.GatherWood(log.get().pos(), order.containerPos(), need)), REASON_ORDER_STOCK_WOOD));
	}

	/** Items whose id names a log (vanilla and modded conventions: {@code *_log}, {@code *_stem} counts as wood too). */
	public static int logsIn(Map<String, Integer> contents) {
		int n = 0;
		for (Map.Entry<String, Integer> e : contents.entrySet()) {
			String path = e.getKey().contains(":") ? e.getKey().substring(e.getKey().indexOf(':') + 1) : e.getKey();
			if (path.endsWith("_log") || path.equals("log") || path.endsWith("_stem")) {
				n += e.getValue();
			}
		}
		return n;
	}
}
