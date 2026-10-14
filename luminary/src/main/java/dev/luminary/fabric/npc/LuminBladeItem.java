package dev.luminary.fabric.npc;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.fabric.LuminaryRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * The unique weapon. Indestructible (no durability), heavy hit ({@code damage} points, configured as a fraction of a
 * player's health), and above all identified: every stack carries the owning NPC and a custody instance id. The
 * server's {@code WeaponCustody} knows the single valid instance; a stack with any other id is a stale copy and
 * {@link #inventoryTick} makes it vanish the moment a player carries it. A copy in a chest is inert until picked up.
 */
public final class LuminBladeItem extends Item {
	public static final String NBT_OWNER = "LuminaryOwner";
	public static final String NBT_INSTANCE = "LuminaryInstance";

	private final double damage;

	public LuminBladeItem(Settings settings, double damage) {
		super(settings);
		this.damage = damage;
	}

	/** A stack bound to {@code npc}'s current instance. */
	public static ItemStack bound(Item item, LuminaryId npc, UUID instance) {
		ItemStack stack = new ItemStack(item);
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putString(NBT_OWNER, npc.toString());
		nbt.putUuid(NBT_INSTANCE, instance);
		return stack;
	}

	public static Optional<LuminaryId> owner(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		if (nbt == null || !nbt.contains(NBT_OWNER)) {
			return Optional.empty();
		}
		try {
			return Optional.of(LuminaryId.parse(nbt.getString(NBT_OWNER)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public static Optional<UUID> instance(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.containsUuid(NBT_INSTANCE) ? Optional.of(nbt.getUuid(NBT_INSTANCE)) : Optional.empty();
	}

	@Override
	public Multimap<EntityAttribute, EntityAttributeModifier> getAttributeModifiers(EquipmentSlot slot) {
		if (slot != EquipmentSlot.MAINHAND) {
			return super.getAttributeModifiers(slot);
		}
		return ImmutableMultimap.of(
				EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(ATTACK_DAMAGE_MODIFIER_ID, "Weapon modifier", damage - 1.0, EntityAttributeModifier.Operation.ADDITION),
				EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(ATTACK_SPEED_MODIFIER_ID, "Weapon modifier", -2.6, EntityAttributeModifier.Operation.ADDITION));
	}

	@Override
	public boolean isDamageable() {
		return false;
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}

	@Override
	public boolean isEnchantable(ItemStack stack) {
		return false;
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient || !(entity instanceof PlayerEntity player) || world.getTime() % 20 != 0) {
			return;
		}
		LuminaryRuntime.get().ifPresent(rt -> rt.npc().behaviour().reconcileHeldBlade(stack, player));
	}
}
