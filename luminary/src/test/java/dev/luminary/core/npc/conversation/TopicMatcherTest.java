package dev.luminary.core.npc.conversation;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TopicMatcherTest {
	private static Topic of(String text) {
		return TopicMatcher.match(text).orElseThrow(() -> new AssertionError("no topic for: " + text));
	}

	@Test
	void russianAndEnglishLinesLandOnTheirTopics() {
		assertEquals(Topic.GREETING, of("Приииивет, Люмин!!!"));
		assertEquals(Topic.GREETING, of("Добрый вечер"));
		assertEquals(Topic.GREETING, of("hi"));
		assertEquals(Topic.FAREWELL, of("ну всё, пока"));
		assertEquals(Topic.THANKS, of("спасибо большое"));
		assertEquals(Topic.HOW_ARE_YOU, of("как дела?"));
		assertEquals(Topic.WHO_ARE_YOU, of("ты кто вообще такой"));
		assertEquals(Topic.WHO_ARE_YOU, of("ты бот?"));
		assertEquals(Topic.SHOP, of("что продаёшь?"));
		assertEquals(Topic.SHOP, of("what do you sell"));
		assertEquals(Topic.QUESTS, of("есть какие-нибудь задания?"));
		assertEquals(Topic.QUESTS, of("any quests for me?"));
		assertEquals(Topic.POINTS, of("сколько у меня очков"));
		assertEquals(Topic.KARMA, of("какая у меня карма"));
		assertEquals(Topic.KARMA, of("ты на меня зол?"));
		assertEquals(Topic.DONATE, of("хочу пожертвовать"));
		assertEquals(Topic.SWORD, of("расскажи про свой меч"));
		assertEquals(Topic.HARP, of("что за арфа"));
		assertEquals(Topic.ARMOUR, of("откуда у тебя крылья"));
		assertEquals(Topic.HELP, of("что ты умеешь?"));
		assertEquals(Topic.FOLLOW_ME, of("иди за мной"));
		assertEquals(Topic.FOLLOW_ME, of("follow me please"));
		assertEquals(Topic.STOP, of("стой"));
		assertEquals(Topic.STOP, of("stop following me"));
		assertEquals(Topic.JOKE, of("расскажи анекдот"));
		assertEquals(Topic.INSULT, of("ты тупой"));
		assertEquals(Topic.COMPLIMENT, of("ты лучший"));
		assertEquals(Topic.LORE, of("что такое каталог?"));
		assertEquals(Topic.TIME, of("который час"));
		assertEquals(Topic.WEATHER, of("какая погода"));
		assertEquals(Topic.REMEMBER_ME, of("ты меня помнишь?"));
		assertEquals(Topic.WHAT_DO_YOU_DO, of("чем занимаешься"));
	}

	@Test
	void yesAndNoOnlyAsAWholeShortLine() {
		assertEquals(Topic.YES, of("да"));
		assertEquals(Topic.YES, of("Ок!"));
		assertEquals(Topic.NO, of("нет"));
		assertNotEquals(Optional.of(Topic.YES), TopicMatcher.match("да ну тебя, расскажи про меч"));
	}

	@Test
	void shortStemsNeedWholeWordsAndUnknownLinesHaveNoTopic() {
		assertTrue(TopicMatcher.match("Куда ведёт эта дорога?").isEmpty(), "'ку' inside a word is not a greeting");
		assertTrue(TopicMatcher.match("hippo").isEmpty(), "'hi' inside a word is not a greeting");
		assertTrue(TopicMatcher.match("Сколько стоит железо в соседней деревне").isEmpty());
		assertEquals("привеет люмин", TopicMatcher.normalise("Привееет, Люмин!!!"), "three or more of a letter become two");
		assertEquals("привет люмин", TopicMatcher.dedupe(TopicMatcher.normalise("Привееет, Люмин!!!")));
	}

	@Test
	void placeholdersAreFilledAndUnknownOnesKept() {
		assertEquals("У тебя 7 очков, Ann. {x}", TopicMatcher.fill("У тебя {points} очков, {player}. {x}", Map.of("points", "7", "player", "Ann")));
	}
}
