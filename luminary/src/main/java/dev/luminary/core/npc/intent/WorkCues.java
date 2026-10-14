package dev.luminary.core.npc.intent;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic guard between the model planner and the world: a model may only turn words into an action when
 * the words themselves carry a cue of work (a verb or object of gathering, fetching, smelting, storing or coming
 * over). Compliments, questions and statements ("this is your name, I am X") never become tasks, however the model
 * reads them. English and Russian stems.
 */
public final class WorkCues {
	private static final List<String> CUES = List.of(
			// gathering
			"chop", "cut", "log", "wood", "tree", "gather", "руб", "дерев", "брев", "дров", "лес", "собер", "собра", "напил", "насобир", "заготов",
			// fetching and storing
			"bring", "fetch", "take", "get me", "give me", "grab", "carry", "принес", "возьм", "достан", "дай ", "подай", "сходи", "забер", "захвати", "притащ", "отнес", "перенес",
			"put", "store", "deposit", "unload", "chest", "container", "слож", "полож", "выгруз", "сундук", "ящик", "убери",
			// smelting
			"smelt", "melt", "cook", "furnace", "ore", "плав", "печ", "руд", "обожг", "пережг", "запеч", "выплав",
			// coming over
			"come", "here", "follow", "подойд", "иди", "сюда", "ко мне", "пошли", "пойдём", "пойдем");

	private WorkCues() {
	}

	public static boolean mentionsWork(String utterance) {
		if (utterance == null) {
			return false;
		}
		String t = utterance.toLowerCase(Locale.ROOT);
		for (String cue : CUES) {
			if (t.contains(cue)) {
				return true;
			}
		}
		return false;
	}
}
