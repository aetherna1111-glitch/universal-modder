package dev.luminary.core.npc.character;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterSheetTest {
	private final CharacterSheet sheet = new CharacterSheet(2, "woke with the city", List.of("evidence first"), List.of("brief"), List.of("invent lore"),
			List.of("the blade returns"), Map.of("gaze", Map.of("en", List.of("Yes?", "Hm."), "ru", List.of("Да?")), "warning", Map.of("en", List.of("Stop."))));

	@Test
	void picksDeterministicallyAndWraps() {
		assertEquals("Yes?", sheet.line("gaze", "en", 0).orElseThrow());
		assertEquals("Hm.", sheet.line("gaze", "en", 1).orElseThrow());
		assertEquals("Yes?", sheet.line("gaze", "en", 2).orElseThrow());
		assertEquals("Hm.", sheet.line("gaze", "en", -1).orElseThrow());
	}

	@Test
	void fallsBackToEnglishAndToEmpty() {
		assertEquals("Да?", sheet.line("gaze", "ru", 0).orElseThrow());
		assertEquals("Stop.", sheet.line("warning", "ru", 0).orElseThrow(), "no Russian warning: English is used");
		assertTrue(sheet.line("nothing", "en", 0).isEmpty());
		assertTrue(CharacterSheet.EMPTY.line("gaze", "en", 0).isEmpty());
		assertTrue(CharacterSheet.EMPTY.isEmpty());
		assertFalse(sheet.isEmpty());
	}

	@Test
	void promptLinesLabelEverySection() {
		List<String> p = sheet.promptLines();
		assertEquals(List.of("Origin: woke with the city", "Principle: evidence first", "Voice: brief", "Never: invent lore", "Known: the blade returns"), p);
		assertEquals(2, sheet.version());
	}
}
