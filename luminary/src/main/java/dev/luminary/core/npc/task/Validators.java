package dev.luminary.core.npc.task;

import dev.luminary.core.catalog.Result;

/** The concrete rules. Each is a few lines and independently unit-tested. */
final class Validators {
	private Validators() {
	}

	static final class Supported implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			return action instanceof NpcAction.GatherWood ? Result.OK : Result.fail(REASON_UNSUPPORTED);
		}
	}

	static final class Arguments implements ActionValidator {
		private final TaskRules rules;

		Arguments(TaskRules rules) {
			this.rules = rules;
		}

		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			NpcAction.GatherWood g = (NpcAction.GatherWood) action;
			if (g.maxLogs() < 1 || g.maxLogs() > rules.maxLogsPerTask() || g.targetPos() == g.containerPos()) {
				return Result.fail(REASON_BAD_ARGUMENTS);
			}
			return Result.OK;
		}
	}

	static final class Permitted implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			return ctx.permitted(action) ? Result.OK : Result.fail(REASON_NOT_PERMITTED);
		}
	}

	static final class Loaded implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			NpcAction.GatherWood g = (NpcAction.GatherWood) action;
			return ctx.isLoaded(g.targetPos()) && ctx.isLoaded(g.containerPos()) ? Result.OK : Result.fail(REASON_NOT_LOADED);
		}
	}

	static final class InRange implements ActionValidator {
		private final TaskRules rules;

		InRange(TaskRules rules) {
			this.rules = rules;
		}

		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			NpcAction.GatherWood g = (NpcAction.GatherWood) action;
			double max = rules.maxRangeBlocks() * rules.maxRangeBlocks();
			return ctx.distanceSq(g.targetPos()) <= max && ctx.distanceSq(g.containerPos()) <= max ? Result.OK : Result.fail(REASON_OUT_OF_RANGE);
		}
	}

	static final class TargetIsLog implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			NpcAction.GatherWood g = (NpcAction.GatherWood) action;
			return ctx.kindAt(g.targetPos()) == ActionContext.BlockKind.LOG ? Result.OK : Result.fail(REASON_TARGET_NOT_LOG);
		}
	}

	static final class ContainerAuthorized implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			NpcAction.GatherWood g = (NpcAction.GatherWood) action;
			return ctx.containerAuthorized(g.containerPos()) ? Result.OK : Result.fail(REASON_CONTAINER_UNAUTHORIZED);
		}
	}

	static final class ContainerIsInventoryWithRoom implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			NpcAction.GatherWood g = (NpcAction.GatherWood) action;
			if (ctx.kindAt(g.containerPos()) != ActionContext.BlockKind.CONTAINER) {
				return Result.fail(REASON_CONTAINER_NOT_INVENTORY);
			}
			int free = ctx.containerFreeSlots(g.containerPos());
			if (free < 0) {
				return Result.fail(REASON_CONTAINER_NOT_INVENTORY);
			}
			return free > 0 ? Result.OK : Result.fail(REASON_CONTAINER_FULL);
		}
	}
}
