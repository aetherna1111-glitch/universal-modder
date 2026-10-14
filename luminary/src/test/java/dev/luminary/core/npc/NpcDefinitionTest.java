package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcDefinitionTest {
	@Test
	void aliasesAddressTheNpcCaseInsensitivelyAndWithRussianCases() {
		NpcDefinition lumin = new NpcDefinition(LuminaryId.of("npc/lumin"), "n", LuminaryId.of("d"), List.of(), List.of("Lumin", " Люмин "));
		assertEquals(List.of("lumin", "люмин"), lumin.aliases());
		assertTrue(lumin.isAddressed("Lumin, chop some wood"));
		assertTrue(lumin.isAddressed("привет, ЛЮМИН!"));
		assertTrue(lumin.isAddressed("скажи Люмину, что я пришёл"));
		assertFalse(lumin.isAddressed("hello everyone"));
		assertFalse(lumin.isAddressed(null));
	}

	@Test
	void defaultAliasIsTheLastPathSegment() {
		NpcDefinition vesna = new NpcDefinition(LuminaryId.of("npc/archive/vesna"), "n", LuminaryId.of("d"));
		assertEquals(List.of("vesna"), vesna.aliases());
		assertTrue(vesna.isAddressed("Vesna?"));
	}
}
