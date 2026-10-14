package dev.luminary.core.npc.intent;

import dev.luminary.core.npc.task.NpcAction;

import java.util.Optional;

/**
 * A planner's output: maybe an action (still unvalidated; {@code NpcService.assign} decides), and how the NPC
 * answers. {@code replyKey} is a translation key (deterministic planners), {@code literalReply} free text (a model).
 */
public record Intent(Optional<NpcAction> action, Optional<String> replyKey, Optional<String> literalReply, String plannerId, Optional<Grant> grant) {
	/** The player hands the NPC a container (or takes it back): {@code allow} true authorizes, false revokes. */
	public record Grant(long containerPos, boolean allow) {}

	public Intent(Optional<NpcAction> action, Optional<String> replyKey, Optional<String> literalReply, String plannerId) {
		this(action, replyKey, literalReply, plannerId, Optional.empty());
	}

	public static final String REPLY_CONTAINER_GRANTED = "npc.luminary.intent.container_granted";
	public static final String REPLY_CONTAINER_REVOKED = "npc.luminary.intent.container_revoked";
	public static final String REPLY_LOOK_AT_CONTAINER = "npc.luminary.intent.look_at_container";

	public static Intent grant(long containerPos, boolean allow, String plannerId) {
		return new Intent(Optional.empty(), Optional.of(allow ? REPLY_CONTAINER_GRANTED : REPLY_CONTAINER_REVOKED), Optional.empty(), plannerId,
				Optional.of(new Grant(containerPos, allow)));
	}
	public static final String REPLY_WILL_DO = "npc.luminary.intent.will_do";
	public static final String REPLY_NO_TREES = "npc.luminary.intent.no_trees";
	public static final String REPLY_NO_CONTAINER = "npc.luminary.intent.no_container";
	public static final String REPLY_NO_SUCH_ITEM = "npc.luminary.intent.no_such_item";
	public static final String REPLY_BUSY = "npc.luminary.intent.busy";
	public static final String REPLY_WHERE = "npc.luminary.intent.where";
	public static final String REPLY_NO_FURNACE = "npc.luminary.intent.no_furnace";

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
