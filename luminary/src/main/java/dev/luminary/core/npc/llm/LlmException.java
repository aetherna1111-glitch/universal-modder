package dev.luminary.core.npc.llm;

/** Every provider failure is one of these so callers can fall back without inspecting transport details. */
public class LlmException extends RuntimeException {
	public enum Kind { UNAVAILABLE, TIMEOUT, TRANSPORT, HTTP_STATUS, TOO_LARGE, BAD_RESPONSE, REFUSED, BUSY }

	private final Kind kind;

	public LlmException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public LlmException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}
}
