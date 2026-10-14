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

	public static final LuminArmorItem VESTMENT_HELMET = new LuminArmorItem(net.minecraft.item.ArmorItem.Type.HELMET, new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
	public static final LuminArmorItem VESTMENT_CHESTPLATE = new LuminArmorItem(net.minecraft.item.ArmorItem.Type.CHESTPLATE, new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
	public static final LuminArmorItem VESTMENT_LEGGINGS = new LuminArmorItem(net.minecraft.item.ArmorItem.Type.LEGGINGS, new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
	public static final LuminArmorItem VESTMENT_BOOTS = new LuminArmorItem(net.minecraft.item.ArmorItem.Type.BOOTS, new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
	public static final java.util.List<LuminArmorItem> VESTMENTS = java.util.List.of(VESTMENT_HELMET, VESTMENT_CHESTPLATE, VESTMENT_LEGGINGS, VESTMENT_BOOTS);

	/** Lumin's shop: the dust he sells by the handful, the one seal no trader has, and the harp he plays once his blade is gone. */
	public static final LightstoneDustItem LIGHTSTONE_DUST = new LightstoneDustItem(new Item.Settings().rarity(Rarity.UNCOMMON));
	public static final EnvoySealItem ENVOY_SEAL = new EnvoySealItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
	public static final AngelicHarpItem ANGELIC_HARP = new AngelicHarpItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());

	private LuminaryItems() {
	}

	public static void register() {
		Registry.register(Registries.ITEM, BLADE_ID, LUMIN_BLADE);
		Registry.register(Registries.SCREEN_HANDLER, new Identifier(Luminary.MOD_ID, "gear_view"), GearViewScreenHandler.TYPE);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "vestment_helmet"), VESTMENT_HELMET);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "vestment_chestplate"), VESTMENT_CHESTPLATE);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "vestment_leggings"), VESTMENT_LEGGINGS);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "vestment_boots"), VESTMENT_BOOTS);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "lightstone_dust"), LIGHTSTONE_DUST);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "envoy_seal"), ENVOY_SEAL);
		Registry.register(Registries.ITEM, new Identifier(Luminary.MOD_ID, "angelic_harp"), ANGELIC_HARP);
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(LIGHTSTONE_DUST));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(ENVOY_SEAL));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
			entries.add(HolySwordItem.unbound(LUMIN_BLADE));   // a free blade, fully prepared
			entries.add(AngelicHarpItem.unbound(ANGELIC_HARP));
			for (LuminArmorItem piece : VESTMENTS) {
				entries.add(LuminArmorItem.unbound(piece));
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (source.isOf(net.minecraft.entity.damage.DamageTypes.FALL) && LuminArmorItem.refusesFall(entity)) {
				return false;
			}
			LuminArmorItem.cinders(entity, source);
			return true;
		});
	}
}
