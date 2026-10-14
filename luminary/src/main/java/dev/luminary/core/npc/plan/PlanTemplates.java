package dev.luminary.core.npc.plan;

import dev.luminary.core.npc.task.NpcAction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Authored plan shapes. Deterministic; a model never writes plans, it may only ask for one of these by name. */
public final class PlanTemplates {
	private PlanTemplates() {
	}

	/** Gather {@code logs} logs at {@code tree} into {@code container}, then make sure everything carried is deposited. */
	public static NpcPlan stockWood(long tree, long container, int logs, Instant now, UUID requestedBy) {
		UUID id = UUID.randomUUID();
		return new NpcPlan(id, "stock " + logs + " logs", List.of(
				new PlanStep("gather", new NpcAction.GatherWood(tree, container, logs), new PlanStep.Precondition.None()),
				new PlanStep("deposit", new NpcAction.Deposit(container), new PlanStep.Precondition.Carrying())), now, requestedBy);
	}

	/** Take {@code count} of {@code item} from {@code container}, smelt them in {@code furnace}, bring the result back. */
	public static NpcPlan smeltFromStock(long container, long furnace, String item, int count, Instant now, UUID requestedBy) {
		UUID id = UUID.randomUUID();
		return new NpcPlan(id, "smelt " + count + " " + item, List.of(
				new PlanStep("smelt", new NpcAction.Smelt(furnace, container, item, count), new PlanStep.Precondition.ContainerHas(container, item, 1)),
				new PlanStep("deposit", new NpcAction.Deposit(container), new PlanStep.Precondition.Carrying())), now, requestedBy);
	}
}
