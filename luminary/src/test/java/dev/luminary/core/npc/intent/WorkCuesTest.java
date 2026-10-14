package dev.luminary.core.npc.intent;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkCuesTest {
	@Test
	void onlyWordsAboutWorkMayBecomeActions() {
		assertTrue(WorkCues.mentionsWork("наруби 5 брёвен"));
		assertTrue(WorkCues.mentionsWork("Lumin, chop some wood"));
		assertTrue(WorkCues.mentionsWork("принеси уголь из сундука"));
		assertTrue(WorkCues.mentionsWork("переплавь железо"));
		assertTrue(WorkCues.mentionsWork("иди ко мне"));
		assertFalse(WorkCues.mentionsWork("ты прекрасен"));
		assertFalse(WorkCues.mentionsWork("почему ты повторяешь за мной?"));
		assertFalse(WorkCues.mentionsWork("это твое имя, я aethernal"));
		assertFalse(WorkCues.mentionsWork(null));
	}

	@Test
	void theAddressIsStrippedBeforeTheModelSeesTheLine() {
		NpcDefinition def = new NpcDefinition(LuminaryId.parse("luminary:npc/lumin"), "n", LuminaryId.parse("luminary:dialogue/lumin"), List.of(), List.of("Lumin", "Люмин"));
		assertEquals("ты прекрасен", def.stripAddress("Люмин, ты прекрасен"));
		assertEquals("это твое имя, я aethernal", def.stripAddress("Люмин, это твое имя, я aethernal"));
		assertEquals("are you there?", def.stripAddress("lumin are you there?"));
		assertEquals("привет", def.stripAddress("Люмину привет"));
		assertEquals("Люмин", def.stripAddress("Люмин"), "a bare name stays as it was");
		assertEquals("hello there", def.stripAddress("hello there"));
	}
}
