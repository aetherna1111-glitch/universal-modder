package dev.luminary.fabric.client;

import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelElement;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The item models go through Minecraft's own model deserializer (available in the development jar), so a model the
 * client would refuse or silently empty is caught here. The volumetric sword must not descend from
 * {@code builtin/generated} (that parent replaces the elements with a flat layer0 silhouette: an invisible item when
 * layer0 is missing, which is exactly what happened once).
 */
class ItemModelsParseTest {
	private static com.google.gson.JsonObject raw(String path) throws Exception {
		try (Reader r = new InputStreamReader(ItemModelsParseTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
			return com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
		}
	}

	private static JsonUnbakedModel load(String path) throws Exception {
		try (Reader r = new InputStreamReader(ItemModelsParseTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
			assertNotNull(r, path);
			return JsonUnbakedModel.deserialize(r);
		}
	}

	@Test
	void theSwordIsAVolumetricModelWithNoGeneratedParent() throws Exception {
		JsonUnbakedModel sword = load("/assets/luminary/models/item/holy_sword_of_lumin.json");
		assertFalse(raw("/assets/luminary/models/item/holy_sword_of_lumin.json").has("parent"), "no parent: a generated parent would discard the elements");
		assertTrue(sword.getElements().size() >= 20, "elements: " + sword.getElements().size());
		for (ModelElement e : sword.getElements()) {
			assertFalse(e.faces.isEmpty(), "every element has faces");
			e.faces.values().forEach(f -> assertEquals("#palette", f.textureId));
		}
		assertNotNull(sword.getTransformations().thirdPersonRightHand);
		assertEquals("luminary:item/holy_sword_palette", sword.resolveSprite("palette").getTextureId().toString());
	}

	@Test
	void theVestmentIconsAreGeneratedFromTheirLayer() throws Exception {
		for (String n : new String[] {"helmet", "chestplate", "leggings", "boots"}) {
			JsonUnbakedModel m = load("/assets/luminary/models/item/vestment_" + n + ".json");
			assertEquals("minecraft:item/generated", raw("/assets/luminary/models/item/vestment_" + n + ".json").get("parent").getAsString());
			assertEquals("luminary:item/vestment_" + n, m.resolveSprite("layer0").getTextureId().toString());
		}
	}
}
