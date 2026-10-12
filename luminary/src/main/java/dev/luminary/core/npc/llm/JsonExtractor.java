package dev.luminary.core.npc.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.Optional;

/** Finds the one JSON object in a model reply: bare, inside ``` fences, or surrounded by prose. */
public final class JsonExtractor {
	private JsonExtractor() {
	}

	public static Optional<JsonObject> extractObject(String text) {
		if (text == null) {
			return Optional.empty();
		}
		String t = text.trim();
		Optional<JsonObject> direct = parse(t);
		if (direct.isPresent()) {
			return direct;
		}
		int fence = t.indexOf("```");
		if (fence >= 0) {
			int start = t.indexOf('\n', fence);
			int end = t.indexOf("```", fence + 3);
			if (start >= 0 && end > start) {
				Optional<JsonObject> fenced = parse(t.substring(start + 1, end).trim());
				if (fenced.isPresent()) {
					return fenced;
				}
			}
		}
		int open = t.indexOf('{');
		int close = t.lastIndexOf('}');
		if (open >= 0 && close > open) {
			return parse(t.substring(open, close + 1));
		}
		return Optional.empty();
	}

	private static Optional<JsonObject> parse(String s) {
		if (!s.startsWith("{")) {
			return Optional.empty();
		}
		try {
			JsonElement e = JsonParser.parseString(s);
			return e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
		} catch (JsonSyntaxException | IllegalStateException e) {
			return Optional.empty();
		}
	}
}
