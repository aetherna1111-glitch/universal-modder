package dev.luminary.core.npc.task;

import dev.luminary.core.catalog.Result;

import java.util.OptionalLong;

/** The concrete rules. Each is a few lines and independently unit-tested. */
final class Validators {
	private Validators() {
	}

	/** The container position an action uses, if any. */
	static OptionalLong container(NpcAction a) {
		if (a instanceof NpcAction.GatherWood g) return OptionalLong.of(g.containerPos());
		if (a instanceof NpcAction.Deposit d) return OptionalLong.of(d.containerPos());
		if (a instanceof NpcAction.Withdraw w) return OptionalLong.of(w.containerPos());
		return OptionalLong.empty();
	}

	/** The position the NPC walks to first, if it is not the container. */
	static OptionalLong target(NpcAction a) {
		if (a instanceof NpcAction.GatherWood g) return OptionalLong.of(g.targetPos());
		if (a instanceof NpcAction.MoveTo m) return OptionalLong.of(m.targetPos());
		return OptionalLong.empty();
	}

	static final class Supported implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			return action instanceof NpcAction.GatherWood || action instanceof NpcAction.MoveTo
					|| action instanceof NpcAction.Deposit || action instanceof NpcAction.Withdraw
					? Result.OK : Result.fail(REASON_UNSUPPORTED);
		}
	}

	static final class Arguments implements ActionValidator {
		private final TaskRules rules;

		Arguments(TaskRules rules) {
			this.rules = rules;
		}

		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			if (action instanceof NpcAction.GatherWood g) {
				if (g.maxLogs() < 1 || g.maxLogs() > rules.maxLogsPerTask() || g.targetPos() == g.containerPos()) {
					return Result.fail(REASON_BAD_ARGUMENTS);
				}
			} else if (action instanceof NpcAction.Withdraw w) {
				if (w.count() < 1 || w.count() > rules.maxWithdrawCount() || w.itemId() == null || w.itemId().isBlank()) {
					return Result.fail(REASON_BAD_ARGUMENTS);
				}
				if (!ctx.itemExists(w.itemId())) {
					return Result.fail(REASON_UNKNOWN_ITEM);
				}
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
			OptionalLong t = target(action), c = container(action);
			if (t.isPresent() && !ctx.isLoaded(t.getAsLong())) return Result.fail(REASON_NOT_LOADED);
			if (c.isPresent() && !ctx.isLoaded(c.getAsLong())) return Result.fail(REASON_NOT_LOADED);
			return Result.OK;
		}
	}

	static final class InRange implements ActionValidator {
		private final TaskRules rules;

		InRange(TaskRules rules) {
			this.rules = rules;
		}

		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			double max = rules.maxRangeBlocks() * rules.maxRangeBlocks();
			OptionalLong t = target(action), c = container(action);
			if (t.isPresent() && ctx.distanceSq(t.getAsLong()) > max) return Result.fail(REASON_OUT_OF_RANGE);
			if (c.isPresent() && ctx.distanceSq(c.getAsLong()) > max) return Result.fail(REASON_OUT_OF_RANGE);
			return Result.OK;
		}
	}

	/** Gather needs a log; moving somewhere needs nothing special (the executor fails if it cannot get there). */
	static final class Target implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			if (action instanceof NpcAction.GatherWood g && ctx.kindAt(g.targetPos()) != ActionContext.BlockKind.LOG) {
				return Result.fail(REASON_TARGET_NOT_LOG);
			}
			return Result.OK;
		}
	}

	/** Any container used must be authorized and be an inventory. */
	static final class Container implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			OptionalLong c = container(action);
			if (c.isEmpty()) {
				return Result.OK;
			}
			if (!ctx.containerAuthorized(c.getAsLong())) {
				return Result.fail(REASON_CONTAINER_UNAUTHORIZED);
			}
			if (ctx.kindAt(c.getAsLong()) != ActionContext.BlockKind.CONTAINER || ctx.containerFreeSlots(c.getAsLong()) < 0) {
				return Result.fail(REASON_CONTAINER_NOT_INVENTORY);
			}
			return Result.OK;
		}
	}

	/** Room and contents: something to deposit into a container with space; something to withdraw into free hands. */
	static final class Inventories implements ActionValidator {
		@Override
		public Result validate(NpcAction action, ActionContext ctx) {
			if (action instanceof NpcAction.GatherWood g) {
				return ctx.containerFreeSlots(g.containerPos()) > 0 ? Result.OK : Result.fail(REASON_CONTAINER_FULL);
			}
			if (action instanceof NpcAction.Deposit d) {
				if (ctx.heldItemCount() <= 0) return Result.fail(REASON_NOTHING_TO_DEPOSIT);
				return ctx.containerFreeSlots(d.containerPos()) > 0 ? Result.OK : Result.fail(REASON_CONTAINER_FULL);
			}
			if (action instanceof NpcAction.Withdraw w) {
				if (ctx.containerCount(w.containerPos(), w.itemId()) <= 0) return Result.fail(REASON_CONTAINER_LACKS_ITEM);
				return ctx.heldFreeSlots() > 0 ? Result.OK : Result.fail(REASON_HANDS_FULL);
			}
			return Result.OK;
		}
	}
}
