package dev.luminary.core.npc.llm;

/** A one-line instruction telling a small model which language to answer in, derived from the player's text. */
public final class LanguageHint {
	private LanguageHint() {
	}

	public static String forText(String text) {
		if (text == null) {
			return "Answer in the language the player uses.";
		}
		int cyr = 0, lat = 0;
		for (char ch : text.toCharArray()) {
			if (ch >= 0x0400 && ch <= 0x04FF) cyr++;
			else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) lat++;
		}
		if (cyr > lat) {
			return "The player writes in Russian: answer in Russian only (по-русски).";
		}
		if (lat > 0) {
			return "The player writes in English: answer in English.";
		}
		return "Answer in the language the player uses.";
	}
}
