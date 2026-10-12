package dev.luminary.core.ids;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable identifier in the form {@code namespace:path}, with the same grammar as a Minecraft identifier but
 * without any Minecraft dependency so the domain core stays testable on its own.
 * <p>Ids are never renamed once persisted; schema migrations map old ids to new ones.</p>
 */
public record LuminaryId(String namespace, String path) implements Comparable<LuminaryId> {
	public static final String DEFAULT_NAMESPACE = "luminary";
	private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
	private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

	public LuminaryId {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(path, "path");
		if (!NAMESPACE.matcher(namespace).matches()) {
			throw new IllegalArgumentException("invalid namespace '" + namespace + "' (allowed: [a-z0-9_.-])");
		}
		if (!PATH.matcher(path).matches()) {
			throw new IllegalArgumentException("invalid path '" + path + "' (allowed: [a-z0-9_./-])");
		}
	}

	/** Parses {@code "ns:path"}; a bare {@code "path"} gets the {@link #DEFAULT_NAMESPACE}. */
	public static LuminaryId parse(String text) {
		Objects.requireNonNull(text, "text");
		int colon = text.indexOf(':');
		if (colon < 0) {
			return new LuminaryId(DEFAULT_NAMESPACE, text);
		}
		if (text.indexOf(':', colon + 1) >= 0) {
			throw new IllegalArgumentException("more than one ':' in id '" + text + "'");
		}
		return new LuminaryId(text.substring(0, colon), text.substring(colon + 1));
	}

	public static LuminaryId of(String path) {
		return new LuminaryId(DEFAULT_NAMESPACE, path);
	}

	@Override
	public int compareTo(LuminaryId o) {
		int c = namespace.compareTo(o.namespace);
		return c != 0 ? c : path.compareTo(o.path);
	}

	@Override
	public String toString() {
		return namespace + ":" + path;
	}
}
