package dev.luminary.core.catalog;

/** Outcome of a service call: {@code ok} or a stable, translatable {@code reasonKey} (never a free-form message). */
public record Result(boolean ok, String reasonKey) {
	public static final Result OK = new Result(true, "ok");

	public static Result fail(String reasonKey) {
		return new Result(false, reasonKey);
	}
}
