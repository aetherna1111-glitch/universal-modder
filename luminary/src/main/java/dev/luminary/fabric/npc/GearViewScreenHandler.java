package dev.luminary.fabric.npc;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

/**
 * A read-only look at the NPC's gear, shown in the vanilla three-row chest screen (no client code needed): row one
 * is the hands and the armour, rows two and three the bag. Every click is answered with a resync and nothing
 * moves: the slots refuse insertion and removal, quick-moves return nothing, the cursor never gets a stack.
 * The contents are a mirror refreshed every tick while open, so the player sees what the NPC really carries.
 */
public final class GearViewScreenHandler extends GenericContainerScreenHandler {
	private final LuminaryNpcEntity npc;
	private final SimpleInventory mirror;

	private GearViewScreenHandler(int syncId, PlayerInventory playerInventory, LuminaryNpcEntity npc, SimpleInventory mirror) {
		super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, mirror, 3);
		this.npc = npc;
		this.mirror = mirror;
		refresh();
	}

	public static NamedScreenHandlerFactory factory(LuminaryNpcEntity npc) {
		Text name = npc.getCustomName() != null ? npc.getCustomName() : Text.literal(npc.npcId().map(Object::toString).orElse("npc"));
		return new SimpleNamedScreenHandlerFactory((syncId, inv, player) -> new GearViewScreenHandler(syncId, inv, npc, new SimpleInventory(27)),
				Text.translatable("npc.luminary.gear.title", name));
	}

	/** Slot layout of the mirror: 0 main hand, 1 off hand, 2 helmet, 3 chest, 4 legs, 5 boots, 9..17 the bag. */
	void refresh() {
		mirror.setStack(0, npc.getMainHandStack().copy());
		mirror.setStack(1, npc.getOffHandStack().copy());
		mirror.setStack(2, npc.getEquippedStack(EquipmentSlot.HEAD).copy());
		mirror.setStack(3, npc.getEquippedStack(EquipmentSlot.CHEST).copy());
		mirror.setStack(4, npc.getEquippedStack(EquipmentSlot.LEGS).copy());
		mirror.setStack(5, npc.getEquippedStack(EquipmentSlot.FEET).copy());
		for (int i = 0; i < 9; i++) {
			mirror.setStack(9 + i, npc.heldItems().getStack(i).copy());
		}
	}

	@Override
	public void sendContentUpdates() {
		refresh();
		super.sendContentUpdates();
	}

	@Override
	public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
		// look, do not touch: every click just resyncs the mirror and the player's own inventory
		refresh();
		setCursorStack(ItemStack.EMPTY);
		syncState();
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return npc.isAlive() && player.squaredDistanceTo(npc) <= 64.0;
	}

	@Override
	public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
		return false;
	}

	@Override
	public boolean canInsertIntoSlot(net.minecraft.screen.slot.Slot slot) {
		return false;
	}
}
