package dev.luminary.core.npc.intent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a player's words into (at most) one typed {@link dev.luminary.core.npc.task.NpcAction} chosen among the
 * offered candidates. Planners propose; they never execute and never bypass the validators. An empty result means
 * "this was not a request for work": the conversation continues through dialogue.
 */
public interface IntentPlanner {
	String id();

	CompletableFuture<Optional<Intent>> plan(IntentRequest request);

	/** The planner that never sees work in anything; dialogue handles every message. */
	static IntentPlanner none() {
		return new IntentPlanner() {
			@Override public String id() { return "none"; }
			@Override public CompletableFuture<Optional<Intent>> plan(IntentRequest request) {
				return CompletableFuture.completedFuture(Optional.empty());
			}
		};
	}
}
