package dev.luminary.core.npc.intent;

import dev.luminary.core.npc.task.NpcAction;

import java.util.Optional;

/**
 * A planner's output: maybe an action (still unvalidated; {@code NpcService.assign} decides), and how the NPC
 * answers. {@code replyKey} is a translation key (deterministic planners), {@code literalReply} free text (a model).
 */
public record Intent(Optional<NpcAction> action, Optional<String> replyKey, Optional<String> literalReply, String plannerId) {
	public static final String REPLY_WILL_DO = "npc.luminary.intent.will_do";
	public static final String REPLY_NO_TREES = "npc.luminary.intent.no_trees";
	public static final String REPLY_NO_CONTAINER = "npc.luminary.intent.no_container";
	public static final String REPLY_NO_SUCH_ITEM = "npc.luminary.intent.no_such_item";
	public static final String REPLY_BUSY = "npc.luminary.intent.busy";
	public static final String REPLY_WHERE = "npc.luminary.intent.where";

	public static Intent act(NpcAction action, String replyKey, String plannerId) {
		return new Intent(Optional.of(action), Optional.of(replyKey), Optional.empty(), plannerId);
	}

	public static Intent decline(String replyKey, String plannerId) {
		return new Intent(Optional.empty(), Optional.of(replyKey), Optional.empty(), plannerId);
	}

	public boolean hasAction() {
		return action.isPresent();
	}
}
