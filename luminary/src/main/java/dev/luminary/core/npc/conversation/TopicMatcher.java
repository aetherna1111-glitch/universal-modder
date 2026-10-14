package dev.luminary.core.npc.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic reading of a chat line: Russian and English stems, matched on a normalised text (lower case, ё→е,
 * punctuation gone, letters repeated for emphasis collapsed, so "приииивет!!!" is a greeting). Topics are tried in a
 * fixed order (actions, then facts, then small talk); the first whose stem occurs wins. YES and NO match only a whole
 * short line. A line that carries a cue of work ("принеси мне меч") is never a topic: the planner reads it.
 */
public final class TopicMatcher {
	private static final Map<Topic, List<String>> STEMS = new LinkedHashMap<>();

	static {
		STEMS.put(Topic.FOLLOW_ME, List.of("за мной", "следуй", "иди со мной", "пошли со мной", "пойдем со мной", "идем со мной", "сопровожд", "follow me", "come with me", "come along", "walk with me"));
		STEMS.put(Topic.STOP, List.of("стой", "остановись", "стоять", "не ходи за мной", "хватит ходить", "отстань", "останься", "жди здесь", "подожди здесь", "stop following", "stop", "stay here", "wait here", "stay put", "leave me"));
		STEMS.put(Topic.SHOP, List.of("торгов", "продае", "продаеш", "продаш", "купить", "куплю", "магазин", "товар", "что у тебя есть", "что есть на продажу", "почем", "цена", "цены", "ассортимент", "sell", "buy", "shop", "trade", "price", "wares", "what do you have"));
		STEMS.put(Topic.QUESTS, List.of("задани", "поручен", "квест", "что мне сделать", "что нужно сделать", "чем помочь", "чем тебе помочь", "есть работа", "работа есть", "quest", "task", "job for me", "what can i do for you", "anything to do", "errand"));
		STEMS.put(Topic.POINTS, List.of("очк", "балл", "сколько у меня", "мой счет", "моих очков", "points", "how many points", "my score", "my balance"));
		STEMS.put(Topic.KARMA, List.of("карм", "отношен", "как ты ко мне", "доверяешь", "доверие", "как ты меня", "ты на меня зол", "обижен", "karma", "trust me", "do you like me", "standing", "angry at me", "mad at me"));
		STEMS.put(Topic.DONATE, List.of("пожертв", "подаян", "подать тебе", "изумруд", "donat", "alms", "offering", "emerald"));
		STEMS.put(Topic.SWORD, List.of("меч", "клинок", "sword", "blade"));
		STEMS.put(Topic.HARP, List.of("арф", "harp"));
		STEMS.put(Topic.ARMOUR, List.of("брон", "доспех", "облачен", "одежд", "крыль", "нимб", "armor", "armour", "vestment", "wings", "halo"));
		STEMS.put(Topic.REMEMBER_ME, List.of("помнишь меня", "ты меня помнишь", "знаешь меня", "узнаешь меня", "меня зовут", "как меня зовут", "remember me", "know me", "my name is", "what is my name", "whats my name"));
		STEMS.put(Topic.HELP, List.of("помоги", "помощь", "что ты умеешь", "что ты можешь", "что ты делаешь для", "как с тобой", "help", "what can you do", "how do i", "how does this work", "commands"));
		STEMS.put(Topic.WHO_ARE_YOU, List.of("кто ты", "ты кто", "как тебя зовут", "твое имя", "что ты такое", "ты бот", "ты робот", "ты человек", "ты живой", "ты нейросет", "ты ии", "who are you", "your name", "what are you", "are you a bot", "are you human", "are you an ai", "are you alive"));
		STEMS.put(Topic.WHAT_DO_YOU_DO, List.of("чем занимаешься", "что делаешь", "что ты делаешь", "чем ты занят", "чем занят", "what are you doing", "what do you do", "what are you up to"));
		STEMS.put(Topic.HOW_ARE_YOU, List.of("как дела", "как ты", "как сам", "как жизнь", "как настроение", "как поживаешь", "как оно", "что нового", "как здоровье", "как самочувствие", "how are you", "how is it going", "hows it going", "how do you feel", "whats up", "what s up", "how have you been"));
		STEMS.put(Topic.GREETING, List.of("привет", "здравств", "здаров", "здорово", "добрый день", "доброе утро", "добрый вечер", "доброй ночи", "салют", "хай", "хей", "йоу", "ку", "hello", "hi", "hey", "good morning", "good evening", "good day", "greetings", "yo", "sup"));
		STEMS.put(Topic.FAREWELL, List.of("пока", "до встречи", "прощай", "до свидания", "бывай", "спокойной ночи", "увидимся", "до завтра", "я пошел", "я пошла", "мне пора", "bye", "goodbye", "see you", "farewell", "good night", "later", "i have to go", "gotta go"));
		STEMS.put(Topic.THANKS, List.of("спасибо", "благодар", "спс", "пасиб", "thank", "thx", "ty", "cheers"));
		STEMS.put(Topic.JOKE, List.of("шутк", "анекдот", "рассмеши", "пошути", "смешн", "joke", "funny", "make me laugh"));
		STEMS.put(Topic.INSULT, List.of("дурак", "тупой", "тупица", "идиот", "ненавижу", "урод", "заткнись", "отвали", "дебил", "stupid", "idiot", "hate you", "dumb", "shut up", "moron", "loser"));
		STEMS.put(Topic.COMPLIMENT, List.of("ты лучший", "молодец", "крут", "ты классный", "люблю тебя", "обожаю", "ты хороший", "спасибо что", "love you", "you are great", "you re great", "awesome", "you are the best", "good job", "well done", "nice one"));
		STEMS.put(Topic.LORE, List.of("каталог", "неизвестн", "расскажи о себе", "расскажи про себя", "откуда ты", "история", "что это за место", "кто тебя создал", "зачем ты", "catalog", "the unknown", "tell me about yourself", "where are you from", "lore", "what is this place", "who made you", "why are you here"));
		STEMS.put(Topic.WHERE, List.of("где ты", "где мы", "где находишься", "где я", "куда идти", "where are you", "where are we", "where is this", "where am i"));
		STEMS.put(Topic.TIME, List.of("который час", "сколько времени", "какое время", "сколько сейчас", "утро или", "what time", "time is it", "is it night", "is it morning"));
		STEMS.put(Topic.WEATHER, List.of("погод", "дожд", "гроза", "снег", "weather", "rain", "storm", "snow"));
	}

	private static final List<String> YES = List.of("да", "ага", "угу", "ок", "окей", "хорошо", "ладно", "конечно", "давай", "yes", "yeah", "yep", "ok", "okay", "sure", "fine", "alright");
	private static final List<String> NO = List.of("нет", "неа", "не", "не надо", "не хочу", "no", "nope", "nah", "no thanks");

	/** Short stems that are words of their own ("hi" must not fire inside "hippo"); every other stem matches inside words ("арф" in "арфа"). */
	private static final java.util.Set<String> WHOLE_WORD = java.util.Set.of("hi", "hey", "yo", "sup", "ku", "ку", "хай", "хей", "йоу", "спс", "ty", "thx", "stop", "stay", "later", "ok");

	private TopicMatcher() {
	}

	/** Lower case, ё→е, punctuation to spaces, three or more repeated letters collapsed to one, single spaces. */
	public static String normalise(String text) {
		if (text == null) {
			return "";
		}
		String t = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
		StringBuilder sb = new StringBuilder(t.length());
		char last = 0;
		int run = 0;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (!Character.isLetterOrDigit(c) && c != ' ') {
				c = ' ';
			}
			if (c == last) {
				run++;
				if (run >= 2 && c != ' ') {
					continue;
				}
				if (c == ' ') {
					continue;
				}
			} else {
				run = 0;
			}
			sb.append(c);
			last = c;
		}
		return sb.toString().strip();
	}

	/** Every run of a repeated letter down to one: "приивет" → "привет" (stems with real double letters are tried on the plain form too). */
	static String dedupe(String normalised) {
		StringBuilder sb = new StringBuilder(normalised.length());
		char last = 0;
		for (int i = 0; i < normalised.length(); i++) {
			char c = normalised.charAt(i);
			if (c != last) {
				sb.append(c);
			}
			last = c;
		}
		return sb.toString();
	}

	public static Optional<Topic> match(String text) {
		String t = normalise(text);
		if (t.isEmpty()) {
			return Optional.empty();
		}
		String padded = " " + t + " ";
		String deduped = " " + dedupe(t) + " ";
		int words = t.split(" ").length;
		if (words <= 2) {
			if (YES.contains(t)) {
				return Optional.of(Topic.YES);
			}
			if (NO.contains(t)) {
				return Optional.of(Topic.NO);
			}
		}
		for (Map.Entry<Topic, List<String>> e : STEMS.entrySet()) {
			for (String stem : e.getValue()) {
				boolean whole = stem.length() <= 2 || WHOLE_WORD.contains(stem);
				boolean hit = whole ? padded.contains(" " + stem + " ") || deduped.contains(" " + stem + " ") : padded.contains(stem) || deduped.contains(stem);
				if (hit) {
					return Optional.of(e.getKey());
				}
			}
		}
		return Optional.empty();
	}

	/** {@code {name}} placeholders filled from {@code values}; unknown ones are left as they are. */
	public static String fill(String line, Map<String, String> values) {
		String out = line;
		for (Map.Entry<String, String> e : values.entrySet()) {
			out = out.replace("{" + e.getKey() + "}", e.getValue());
		}
		return out;
	}
}
