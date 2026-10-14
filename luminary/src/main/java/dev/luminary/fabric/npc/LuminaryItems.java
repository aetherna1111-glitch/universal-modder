package dev.luminary.fabric.npc;

import dev.luminary.fabric.Luminary;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

/** Item registration. The blade's damage is read from config at hit time, so the item itself is config-free. */
public final class LuminaryItems {
	public static final Identifier BLADE_ID = new Identifier(Luminary.MOD_ID, "lumin_blade");
	/** Attribute shown in the tooltip; the real hit uses the configured fraction of the target's health. */
	public static final LuminBladeItem LUMIN_BLADE = new LuminBladeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof(), 10.0);

	private LuminaryItems() {
	}

	public static void register() {
		Registry.register(Registries.ITEM, BLADE_ID, LUMIN_BLADE);
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(LUMIN_BLADE));
	}
}
