package dev.luminary.fabric.npc;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ToolItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * What the NPC does with things it is handed, and how it uses them:
 * <ul>
 *   <li>a tool goes into its 9-slot inventory and is drawn into the main hand while breaking blocks; break time follows
 *       the vanilla formula (tool speed vs block hardness), durability is spent for real, a broken tool is gone and the
 *       next suitable one is used;</li>
 *   <li>armour is worn, one piece per slot: while the worn piece is intact a new one is refused (it stays with the
 *       player); only after the worn piece breaks is the next accepted. Worn armour takes damage like any mob's.</li>
 * </ul>
 */
public final class GearManager {
	public enum Offer { TOOK_TOOL, WEARING, ARMOR_INTACT, INVENTORY_FULL, NOT_GEAR }

	private GearManager() {
	}

	public static boolean isTool(ItemStack s) {
		return !s.isEmpty() && (s.getItem() instanceof ToolItem || s.getItem() instanceof ShearsItem);
	}

	public static boolean isArmor(ItemStack s) {
		return !s.isEmpty() && s.getItem() instanceof ArmorItem;
	}

	/** Handles a player holding something out to the NPC (right-click with the item). Takes at most one item. */
	public static Offer offer(LuminaryNpcEntity npc, PlayerEntity player, ItemStack inHand) {
		if (isArmor(inHand)) {
			EquipmentSlot slot = ((ArmorItem) inHand.getItem()).getSlotType();
			if (!npc.getEquippedStack(slot).isEmpty() || LuminArmorItem.isVestment(npc.getEquippedStack(slot))) {
				return Offer.ARMOR_INTACT;   // the vestments never break, so nothing ever replaces them
			}
			ItemStack one = inHand.copyWithCount(1);
			npc.equipStack(slot, one);
			npc.setEquipmentDropChance(slot, 1.0f);
			if (!player.getAbilities().creativeMode) {
				inHand.decrement(1);
			}
			return Offer.WEARING;
		}
		if (isTool(inHand)) {
			ItemStack one = inHand.copyWithCount(1);
			ItemStack rest = npc.heldItems().addStack(one);
			if (!rest.isEmpty()) {
				return Offer.INVENTORY_FULL;
			}
			npc.heldItems().markDirty();
			if (!player.getAbilities().creativeMode) {
				inHand.decrement(1);
			}
			return Offer.TOOK_TOOL;
		}
		return Offer.NOT_GEAR;
	}

	/** Best tool for {@code state} among the hand and the inventory, by mining speed; empty when bare hands are as good. */
	public static Optional<ItemStack> bestTool(LuminaryNpcEntity npc, BlockState state) {
		ItemStack best = ItemStack.EMPTY;
		float bestSpeed = 1.0f;
		ItemStack hand = npc.getMainHandStack();
		if (isTool(hand) && hand.getMiningSpeedMultiplier(state) > bestSpeed) {
			best = hand;
			bestSpeed = hand.getMiningSpeedMultiplier(state);
		}
		SimpleInventory inv = npc.heldItems();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (isTool(s) && s.getMiningSpeedMultiplier(state) > bestSpeed) {
				best = s;
				bestSpeed = s.getMiningSpeedMultiplier(state);
			}
		}
		return best.isEmpty() ? Optional.empty() : Optional.of(best);
	}

	/** Moves the best tool for {@code state} from the inventory into the main hand (stowing whatever was there). */
	public static void drawTool(LuminaryNpcEntity npc, BlockState state) {
		Optional<ItemStack> best = bestTool(npc, state);
		if (best.isEmpty() || best.get() == npc.getMainHandStack()) {
			return;
		}
		stowTool(npc);
		SimpleInventory inv = npc.heldItems();
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i) == best.get()) {
				inv.removeStack(i);
				break;
			}
		}
		npc.equipStack(EquipmentSlot.MAINHAND, best.get());
		npc.setEquipmentDropChance(EquipmentSlot.MAINHAND, 1.0f);
		inv.markDirty();
	}

	/** Puts the main-hand tool back into the inventory (one object, never a copy), or drops nothing if there is no room. */
	public static void stowTool(LuminaryNpcEntity npc) {
		ItemStack hand = npc.getMainHandStack();
		if (hand.isEmpty()) {
			return;
		}
		ItemStack rest = npc.heldItems().addStack(hand);
		npc.equipStack(EquipmentSlot.MAINHAND, rest);   // stays in hand only if the inventory is full
		npc.heldItems().markDirty();
	}

	/**
	 * Vanilla break time in ticks for {@code state} with {@code tool} (empty = hands): damage per tick is
	 * speed / hardness / 30 with a suitable tool, / 100 otherwise; clamped to [1, 400].
	 */
	public static int breakTicks(ServerWorld world, BlockPos pos, BlockState state, ItemStack tool) {
		float hardness = state.getHardness(world, pos);
		if (hardness < 0) {
			return Integer.MAX_VALUE;
		}
		if (hardness == 0) {
			return 1;
		}
		float speed = tool.isEmpty() ? 1.0f : Math.max(1.0f, tool.getMiningSpeedMultiplier(state));
		boolean suitable = !state.isToolRequired() || (!tool.isEmpty() && tool.isSuitableFor(state));
		double delta = speed / hardness / (suitable ? 30.0 : 100.0);
		return (int) Math.max(1, Math.min(400, Math.ceil(1.0 / delta)));
	}

	/** Spends one durability on the main-hand tool after a block broke; returns true when the tool broke. */
	public static boolean wearTool(LuminaryNpcEntity npc) {
		ItemStack hand = npc.getMainHandStack();
		if (!isTool(hand) || !hand.isDamageable()) {
			return false;
		}
		boolean[] broke = {false};
		hand.damage(1, npc, e -> {
			broke[0] = true;
			e.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		});
		return broke[0] || npc.getMainHandStack().isEmpty();
	}

	/** Human-readable gear summary for commands. */
	public static String describe(LuminaryNpcEntity npc) {
		StringBuilder sb = new StringBuilder("hand=").append(name(npc.getMainHandStack()));
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			sb.append(' ').append(slot.getName()).append('=').append(name(npc.getEquippedStack(slot)));
		}
		sb.append(" bag=[");
		SimpleInventory inv = npc.heldItems();
		boolean first = true;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) {
				sb.append(first ? "" : ", ").append(name(s));
				first = false;
			}
		}
		return sb.append(']').toString();
	}

	private static String name(ItemStack s) {
		if (s.isEmpty()) {
			return "-";
		}
		String n = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath() + (s.getCount() > 1 ? "x" + s.getCount() : "");
		return s.isDamageable() ? n + " (" + (s.getMaxDamage() - s.getDamage()) + "/" + s.getMaxDamage() + ")" : n;
	}
}
