package dev.luminary.core.npc.llm;

import java.util.Objects;

/** One chat message. Roles follow the common chat convention; providers map them to their wire format. */
public record LlmMessage(Role role, String content) {
	public enum Role { SYSTEM, USER, ASSISTANT }

	public LlmMessage {
		Objects.requireNonNull(role);
		Objects.requireNonNull(content);
	}

	public static LlmMessage system(String content) {
		return new LlmMessage(Role.SYSTEM, content);
	}

	public static LlmMessage user(String content) {
		return new LlmMessage(Role.USER, content);
	}

	public static LlmMessage assistant(String content) {
		return new LlmMessage(Role.ASSISTANT, content);
	}

	String wireRole() {
		return role.name().toLowerCase();
	}
}
