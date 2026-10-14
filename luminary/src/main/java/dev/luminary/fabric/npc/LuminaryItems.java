package dev.luminary.fabric.npc;

import dev.luminary.fabric.Luminary;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

/** Item registration. */
public final class LuminaryItems {
	public static final Identifier BLADE_ID = new Identifier(Luminary.MOD_ID, "holy_sword_of_lumin");
	/** The Holy Sword of Lumin; in the NPC's hands the hit is the configured fraction of the target's health, see {@link HolySwordItem}. */
	public static final HolySwordItem LUMIN_BLADE = new HolySwordItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());

	private LuminaryItems() {
	}

	public static void register() {
		Registry.register(Registries.ITEM, BLADE_ID, LUMIN_BLADE);
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(LUMIN_BLADE));
	}
}
