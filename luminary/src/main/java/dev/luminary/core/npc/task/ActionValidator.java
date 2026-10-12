package dev.luminary.core.npc.task;

import dev.luminary.core.catalog.Result;

import java.util.List;

/** One rule. Validators run in order before a task is created and again when a task is resumed from persistence. */
public interface ActionValidator {
	String REASON_UNSUPPORTED = "luminary.task.unsupported_action";
	String REASON_BAD_ARGUMENTS = "luminary.task.bad_arguments";
	String REASON_OUT_OF_RANGE = "luminary.task.out_of_range";
	String REASON_NOT_LOADED = "luminary.task.not_loaded";
	String REASON_TARGET_NOT_LOG = "luminary.task.target_not_log";
	String REASON_CONTAINER_NOT_INVENTORY = "luminary.task.container_not_inventory";
	String REASON_CONTAINER_UNAUTHORIZED = "luminary.task.container_unauthorized";
	String REASON_CONTAINER_FULL = "luminary.task.container_full";
	String REASON_CONTAINER_LACKS_ITEM = "luminary.task.container_lacks_item";
	String REASON_UNKNOWN_ITEM = "luminary.task.unknown_item";
	String REASON_NOTHING_TO_DEPOSIT = "luminary.task.nothing_to_deposit";
	String REASON_HANDS_FULL = "luminary.task.hands_full";
	String REASON_NOT_PERMITTED = "luminary.task.not_permitted";
	String REASON_TASK_ACTIVE = "luminary.task.already_active";
	String REASON_NO_BODY = "luminary.npc.not_present";

	Result validate(NpcAction action, ActionContext ctx);

	/** Runs every validator; the first failure wins. */
	static Result validateAll(List<ActionValidator> validators, NpcAction action, ActionContext ctx) {
		for (ActionValidator v : validators) {
			Result r = v.validate(action, ctx);
			if (!r.ok()) {
				return r;
			}
		}
		return Result.OK;
	}

	/** The standard chain. Order matters: cheap structural checks first, world queries last. */
	static List<ActionValidator> standard(TaskRules rules) {
		return List.of(new Validators.Supported(), new Validators.Arguments(rules), new Validators.Permitted(),
				new Validators.Loaded(), new Validators.InRange(rules), new Validators.Target(),
				new Validators.Container(), new Validators.Inventories());
	}
}
