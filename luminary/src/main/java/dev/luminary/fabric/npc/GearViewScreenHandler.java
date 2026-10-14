package dev.luminary.fabric.npc;

import dev.luminary.core.npc.weapon.WeaponCustody;
import dev.luminary.fabric.LuminaryRuntime;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * A read-only look at the NPC's gear in a screen laid out like the player's inventory: the body in the middle,
 * the four armour slots to its left, the sword and the shield to its right, the bag below, the viewer's own
 * inventory at the bottom. Every click is answered with a resync and nothing moves. The contents are a mirror
 * refreshed every tick while open. The sword slot shows the Holy Sword while it is in the NPC's custody (it is
 * drawn only when fighting, but it is his), the shield slot his shield.
 */
public final class GearViewScreenHandler extends ScreenHandler {
	public static final ExtendedScreenHandlerType<GearViewScreenHandler> TYPE = new ExtendedScreenHandlerType<>(GearViewScreenHandler::fromBuffer);
	/** Mirror layout. */
	public static final int SLOT_HEAD = 0, SLOT_CHEST = 1, SLOT_LEGS = 2, SLOT_FEET = 3, SLOT_SWORD = 4, SLOT_SHIELD = 5, SLOT_BAG = 6, BAG_SIZE = 9, MIRROR_SIZE = 15;

	private final LuminaryNpcEntity npc;   // null on a client that cannot find the entity yet
	private final int entityId;
	private final SimpleInventory mirror = new SimpleInventory(MIRROR_SIZE);

	private GearViewScreenHandler(int syncId, PlayerInventory playerInventory, LuminaryNpcEntity npc, int entityId) {
		super(TYPE, syncId);
		this.npc = npc;
		this.entityId = entityId;
		// armour column (x 8), like the player's own
		for (int i = 0; i < 4; i++) {
			addSlot(new ReadOnlySlot(mirror, SLOT_HEAD + i, 8, 8 + i * 18));
		}
		addSlot(new ReadOnlySlot(mirror, SLOT_SWORD, 77, 26));     // right of the body: the sword
		addSlot(new ReadOnlySlot(mirror, SLOT_SHIELD, 77, 62));    // and the shield, where the off hand sits
		for (int i = 0; i < BAG_SIZE; i++) {                        // the bag, a 3x3 block right of the sword
			addSlot(new ReadOnlySlot(mirror, SLOT_BAG + i, 98 + (i % 3) * 18, 18 + (i / 3) * 18));
		}
		for (int row = 0; row < 3; row++) {                         // the viewer's inventory, read-only here too
			for (int col = 0; col < 9; col++) {
				addSlot(new ReadOnlySlot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new ReadOnlySlot(playerInventory, col, 8 + col * 18, 142));
		}
		refresh();
	}

	private static GearViewScreenHandler fromBuffer(int syncId, PlayerInventory inv, PacketByteBuf buf) {
		int id = buf.readVarInt();
		Entity e = inv.player.getWorld().getEntityById(id);
		return new GearViewScreenHandler(syncId, inv, e instanceof LuminaryNpcEntity n ? n : null, id);
	}

	public static ExtendedScreenHandlerFactory factory(LuminaryNpcEntity npc) {
		Text name = npc.getCustomName() != null ? npc.getCustomName() : Text.literal(npc.npcId().map(Object::toString).orElse("npc"));
		return new ExtendedScreenHandlerFactory() {
			@Override
			public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
				buf.writeVarInt(npc.getId());
			}

			@Override
			public Text getDisplayName() {
				return Text.translatable("npc.luminary.gear.title", name);
			}

			@Override
			public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
				return new GearViewScreenHandler(syncId, inv, npc, npc.getId());
			}
		};
	}

	public int entityId() {
		return entityId;
	}

	/** The body shown in the screen, if the client knows it. */
	public LuminaryNpcEntity npc() {
		return npc;
	}

	void refresh() {
		if (npc == null) {
			return;
		}
		mirror.setStack(SLOT_HEAD, npc.getEquippedStack(EquipmentSlot.HEAD).copy());
		mirror.setStack(SLOT_CHEST, npc.getEquippedStack(EquipmentSlot.CHEST).copy());
		mirror.setStack(SLOT_LEGS, npc.getEquippedStack(EquipmentSlot.LEGS).copy());
		mirror.setStack(SLOT_FEET, npc.getEquippedStack(EquipmentSlot.FEET).copy());
		ItemStack sword = npc.getMainHandStack().isOf(LuminaryItems.LUMIN_BLADE) ? npc.getMainHandStack().copy() : ItemStack.EMPTY;
		if (sword.isEmpty() && npc.npcId().isPresent() && !npc.getWorld().isClient) {
			var id = npc.npcId().get();
			sword = LuminaryRuntime.get().map(rt -> rt.npc().roster().ensureRecord(id)).filter(r -> r.weapon().custody() == WeaponCustody.Custody.NPC)
					.map(r -> LuminaryRuntime.get().orElseThrow().npc().behaviour().blade(id, r.weapon().instanceId())).orElse(ItemStack.EMPTY);
		}
		mirror.setStack(SLOT_SWORD, sword);
		mirror.setStack(SLOT_SHIELD, npc.getOffHandStack().isOf(Items.SHIELD) ? npc.getOffHandStack().copy() : new ItemStack(Items.SHIELD));
		for (int i = 0; i < BAG_SIZE; i++) {
			mirror.setStack(SLOT_BAG + i, npc.heldItems().getStack(i).copy());
		}
	}

	@Override
	public void sendContentUpdates() {
		refresh();
		super.sendContentUpdates();
	}

	@Override
	public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
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
		return npc == null || (npc.isAlive() && player.squaredDistanceTo(npc) <= 64.0);
	}

	@Override
	public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
		return false;
	}

	@Override
	public boolean canInsertIntoSlot(Slot slot) {
		return false;
	}

	/** A slot that shows and never gives or takes. */
	static final class ReadOnlySlot extends Slot {
		ReadOnlySlot(net.minecraft.inventory.Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return false;
		}

		@Override
		public boolean canTakeItems(PlayerEntity player) {
			return false;
		}
	}
}
