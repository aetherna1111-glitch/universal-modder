package dev.luminary.core.ids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuminaryIdTest {
	@Test
	void parsesNamespaceAndPath() {
		LuminaryId id = LuminaryId.parse("thaumcraft:aspect/ignis");
		assertEquals("thaumcraft", id.namespace());
		assertEquals("aspect/ignis", id.path());
		assertEquals("thaumcraft:aspect/ignis", id.toString());
	}

	@Test
	void bareIdGetsDefaultNamespace() {
		assertEquals(new LuminaryId("luminary", "era/town"), LuminaryId.parse("era/town"));
	}

	@Test
	void rejectsInvalidCharactersAndDoubleColon() {
		assertThrows(IllegalArgumentException.class, () -> LuminaryId.parse("Lum:Ore"));
		assertThrows(IllegalArgumentException.class, () -> LuminaryId.parse("a:b:c"));
		assertThrows(IllegalArgumentException.class, () -> LuminaryId.parse("ns:with space"));
	}

	@Test
	void ordersByNamespaceThenPath() {
		assertEquals(-1, Integer.signum(LuminaryId.parse("a:z").compareTo(LuminaryId.parse("b:a"))));
		assertEquals(-1, Integer.signum(LuminaryId.parse("a:a").compareTo(LuminaryId.parse("a:b"))));
	}
}
