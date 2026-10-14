package dev.luminary.fabric.npc;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** The Vestments of the Envoy: above netherite, unbreakable (the items override damageability), layered textures under {@code luminary:lumin}. */
public final class LuminArmorMaterial implements ArmorMaterial {
	public static final LuminArmorMaterial INSTANCE = new LuminArmorMaterial();
	private static final int[] PROTECTION = {4, 7, 9, 4};   // boots, leggings, chestplate, helmet (total 24; netherite is 20)

	/** Zero: {@code ItemStack#isDamageable} looks at the maximum, so the vestments have no durability at all. */
	@Override
	public int getDurability(ArmorItem.Type type) {
		return 0;
	}

	@Override
	public int getProtection(ArmorItem.Type type) {
		return PROTECTION[type.getEquipmentSlot().getEntitySlotId()];
	}

	@Override
	public int getEnchantability() {
		return 0;
	}

	@Override
	public SoundEvent getEquipSound() {
		return SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE;
	}

	@Override
	public Ingredient getRepairIngredient() {
		return Ingredient.EMPTY;
	}

	@Override
	public String getName() {
		return "luminary:lumin";
	}

	@Override
	public float getToughness() {
		return 4.0f;
	}

	@Override
	public float getKnockbackResistance() {
		return 0.15f;
	}
}
