package dev.luminary.core.npc.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded multi-turn context between one NPC and one player: the last {@code maxTurns} exchanges, dropped after
 * {@code idleExpiry} without a new turn so an old topic does not leak into a new meeting. Persisted with the roster;
 * handed to the model provider as "RECENT CONVERSATION" so follow-up questions resolve ("and the second one?").
 */
public final class ConversationContext {
	public enum Role { PLAYER, NPC }

	public record Turn(Role role, String text, Instant at) {
		public Turn {
			Objects.requireNonNull(role);
			text = Objects.requireNonNull(text).strip();
			Objects.requireNonNull(at);
		}
	}

	public static final int DEFAULT_MAX_TURNS = 8;
	public static final Duration DEFAULT_IDLE_EXPIRY = Duration.ofMinutes(10);
	public static final int MAX_TURN_CHARS = 240;

	private final int maxTurns;
	private final Duration idleExpiry;
	private final Deque<Turn> turns = new ArrayDeque<>();

	public ConversationContext() {
		this(DEFAULT_MAX_TURNS, DEFAULT_IDLE_EXPIRY);
	}

	public ConversationContext(int maxTurns, Duration idleExpiry) {
		if (maxTurns < 1 || idleExpiry.isNegative()) {
			throw new IllegalArgumentException("invalid conversation bounds");
		}
		this.maxTurns = maxTurns;
		this.idleExpiry = idleExpiry;
	}

	public void add(Role role, String text, Instant now) {
		expire(now);
		String t = text.length() > MAX_TURN_CHARS ? text.substring(0, MAX_TURN_CHARS - 1) + "…" : text;
		turns.addLast(new Turn(role, t, now));
		while (turns.size() > maxTurns) {
			turns.removeFirst();
		}
	}

	/** Drops everything when the last turn is older than the idle expiry. */
	public void expire(Instant now) {
		Turn last = turns.peekLast();
		if (last != null && Duration.between(last.at(), now).compareTo(idleExpiry) > 0) {
			turns.clear();
		}
	}

	/** The live turns (after expiry), oldest first. */
	public List<Turn> turns(Instant now) {
		expire(now);
		return Collections.unmodifiableList(new ArrayList<>(turns));
	}

	/** Every stored turn without expiry (persistence). */
	public List<Turn> all() {
		return Collections.unmodifiableList(new ArrayList<>(turns));
	}

	public boolean isEmpty() {
		return turns.isEmpty();
	}

	/** Restores a persisted turn without expiry or bounds checks beyond the maximum. Codec only. */
	public void restore(Turn turn) {
		turns.addLast(turn);
		while (turns.size() > maxTurns) {
			turns.removeFirst();
		}
	}
}
