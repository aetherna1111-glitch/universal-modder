package dev.luminary.core.integration;

import dev.luminary.core.ids.LuminaryId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates the Thaumcraft port's observer events (plain maps, see {@code docs/integration/README.md}) into Luminary
 * facts: progression signals and one observation for the NPCs. Pure data in, pure data out; the Fabric layer applies
 * the result. Anything malformed yields {@link Optional#empty()}: an integration can never break the game.
 *
 * <pre>
 * research_completed  → signal/thaumcraft/research/&lt;key&gt; +1, signal/thaumcraft/research_total +1
 * scan_completed      → signal/thaumcraft/scan/&lt;target&gt; +1, signal/thaumcraft/scans +1
 * aspect_discovered   → signal/thaumcraft/aspect/&lt;tag&gt; = max(1)
 * </pre>
 */
public final class ThaumcraftBridge {
	public static final String SOURCE = "thaumcraft";

	public enum Mode { INCREMENT, AT_LEAST }

	public record SignalDelta(LuminaryId signal, Mode mode, long value) {}

	public record Memory(UUID player, String topic, String text) {}

	public record Observation(String type, List<SignalDelta> signals, Memory memory) {}

	private ThaumcraftBridge() {
	}

	public static Optional<Observation> translate(Map<String, Object> event) {
		if (event == null) {
			return Optional.empty();
		}
		String type = string(event.get("type"));
		UUID player = uuid(event.get("player"));
		if (type == null || player == null) {
			return Optional.empty();
		}
		String name = Optional.ofNullable(string(event.get("playerName"))).orElse("someone");
		List<SignalDelta> signals = new ArrayList<>();
		switch (type) {
			case "research_completed" -> {
				String key = string(event.get("key"));
				if (key == null || key.isBlank()) {
					return Optional.empty();
				}
				String slug = slug(key);
				signals.add(new SignalDelta(LuminaryId.of("signal/thaumcraft/research/" + slug), Mode.INCREMENT, 1));
				signals.add(new SignalDelta(LuminaryId.of("signal/thaumcraft/research_total"), Mode.INCREMENT, 1));
				return Optional.of(new Observation(type, signals, new Memory(player, "thaumcraft/research/" + slug,
						name + " completed the Thaumcraft research " + key)));
			}
			case "scan_completed" -> {
				String target = string(event.get("target"));
				if (target == null || target.isBlank()) {
					return Optional.empty();
				}
				String slug = slug(target);
				signals.add(new SignalDelta(LuminaryId.of("signal/thaumcraft/scan/" + slug), Mode.INCREMENT, 1));
				signals.add(new SignalDelta(LuminaryId.of("signal/thaumcraft/scans"), Mode.INCREMENT, 1));
				String aspects = event.get("aspects") instanceof Map<?, ?> m && !m.isEmpty() ? " and learned " + m : "";
				String kind = Optional.ofNullable(string(event.get("scanType"))).orElse("something");
				return Optional.of(new Observation(type, signals, new Memory(player, "thaumcraft/scan/" + slug,
						name + " scanned the " + kind + " " + target + aspects)));
			}
			case "aspect_discovered" -> {
				String tag = string(event.get("aspect"));
				if (tag == null || tag.isBlank()) {
					return Optional.empty();
				}
				signals.add(new SignalDelta(LuminaryId.of("signal/thaumcraft/aspect/" + slug(tag)), Mode.AT_LEAST, 1));
				return Optional.of(new Observation(type, signals, new Memory(player, "thaumcraft/aspect/" + slug(tag),
						name + " discovered the aspect " + tag)));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	/** A research key, item id or aspect tag as a Luminary id path segment: lower case, unknown characters become '_'. */
	public static String slug(String raw) {
		StringBuilder sb = new StringBuilder();
		boolean lastUnderscore = false;
		for (char ch : raw.toLowerCase(Locale.ROOT).toCharArray()) {
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' || ch == '-' || ch == '/';
			if (ch == ':') {
				ch = '/';
				ok = true;
			}
			if (ok) {
				sb.append(ch);
				lastUnderscore = false;
			} else if (!lastUnderscore) {
				sb.append('_');
				lastUnderscore = true;
			}
		}
		String s = sb.toString();
		while (s.startsWith("_") || s.startsWith("/")) s = s.substring(1);
		while (s.endsWith("_") || s.endsWith("/")) s = s.substring(0, s.length() - 1);
		if (s.length() > 80) s = s.substring(0, 80);
		return s.isEmpty() ? "unknown" : s;
	}

	private static String string(Object o) {
		return o instanceof String s ? s : null;
	}

	private static UUID uuid(Object o) {
		if (o instanceof UUID u) {
			return u;
		}
		if (o instanceof String s) {
			try {
				return UUID.fromString(s);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		return null;
	}
}
