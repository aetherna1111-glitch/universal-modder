package dev.luminary.fabric.npc.task;

import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.task.ActionContext;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.fabric.npc.GearManager;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import java.util.Map;
import java.util.Optional;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Answers validator questions from the live server world. Reads only; never loads chunks. */
public final class WorldActionContext implements ActionContext {
	private final ServerWorld world;
	private final LuminaryNpcEntity npc;
	private final NpcRoster.NpcRecord record;

	public WorldActionContext(ServerWorld world, LuminaryNpcEntity npc, NpcRoster.NpcRecord record) {
		this.world = world;
		this.npc = npc;
		this.record = record;
	}

	@Override
	public boolean isLoaded(long pos) {
		BlockPos p = BlockPos.fromLong(pos);
		return world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
	}

	@Override
	public BlockKind kindAt(long pos) {
		return kindAt(world, BlockPos.fromLong(pos));
	}

	public static BlockKind kindAt(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) {
			return BlockKind.AIR;
		}
		if (state.isIn(BlockTags.LOGS)) {
			return BlockKind.LOG;
		}
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof AbstractFurnaceBlockEntity) {
			return BlockKind.FURNACE;
		}
		if (be instanceof Inventory) {
			return BlockKind.CONTAINER;
		}
		return BlockKind.OTHER;
	}

	@Override
	public double distanceSq(long pos) {
		return npc.squaredDistanceTo(Vec3d.ofCenter(BlockPos.fromLong(pos)));
	}

	@Override
	public boolean containerAuthorized(long pos) {
		return record != null && record.authorizedContainers().contains(pos);
	}

	@Override
	public int containerFreeSlots(long pos) {
		BlockEntity be = world.getBlockEntity(BlockPos.fromLong(pos));
		if (!(be instanceof Inventory inv)) {
			return -1;
		}
		int free = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	@Override
	public int containerCount(long pos, String itemId) {
		BlockEntity be = world.getBlockEntity(BlockPos.fromLong(pos));
		if (!(be instanceof Inventory inv)) {
			return -1;
		}
		Identifier id = Identifier.tryParse(itemId);
		if (id == null || !Registries.ITEM.containsId(id)) {
			return 0;
		}
		Item item = Registries.ITEM.get(id);
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && s.isOf(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	@Override
	public boolean itemExists(String itemId) {
		Identifier id = Identifier.tryParse(itemId);
		return id != null && Registries.ITEM.containsId(id);
	}

	@Override
	public boolean smeltable(String itemId) {
		Identifier id = Identifier.tryParse(itemId);
		if (id == null || !Registries.ITEM.containsId(id)) {
			return false;
		}
		return smeltingRecipe(world, new ItemStack(Registries.ITEM.get(id))).isPresent();
	}

	public static Optional<SmeltingRecipe> smeltingRecipe(ServerWorld world, ItemStack input) {
		return world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, new SimpleInventory(input), world);
	}

	@Override
	public int fuelTicksIn(long containerPos) {
		BlockEntity be = world.getBlockEntity(BlockPos.fromLong(containerPos));
		if (!(be instanceof Inventory inv)) {
			return -1;
		}
		return fuelTicks(inv);
	}

	/** Burn ticks all fuel items in an inventory would give. */
	public static int fuelTicks(Inventory inv) {
		Map<Item, Integer> fuel = AbstractFurnaceBlockEntity.createFuelTimeMap();
		long total = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && fuel.containsKey(s.getItem())) {
				total += (long) fuel.get(s.getItem()) * s.getCount();
			}
		}
		return (int) Math.min(Integer.MAX_VALUE, total);
	}

	@Override
	public boolean furnaceAcceptsInput(long furnacePos, String itemId) {
		BlockEntity be = world.getBlockEntity(BlockPos.fromLong(furnacePos));
		Identifier id = Identifier.tryParse(itemId);
		if (!(be instanceof AbstractFurnaceBlockEntity furnace) || id == null || !Registries.ITEM.containsId(id)) {
			return false;
		}
		ItemStack input = furnace.getStack(0);
		return input.isEmpty() || (input.isOf(Registries.ITEM.get(id)) && input.getCount() < input.getMaxCount());
	}

	/** Cargo only: tools and armour in the bag are the NPC's own and never count as something to deposit. */
	@Override
	public int heldItemCount() {
		int n = 0;
		for (int i = 0; i < npc.heldItems().size(); i++) {
			ItemStack s = npc.heldItems().getStack(i);
			if (!s.isEmpty() && !GearManager.isTool(s) && !GearManager.isArmor(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	@Override
	public int heldFreeSlots() {
		int free = 0;
		for (int i = 0; i < npc.heldItems().size(); i++) {
			if (npc.heldItems().getStack(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	@Override
	public boolean permitted(NpcAction action) {
		// This slice: the NPC may act in the world its body is in. Region/dimension rules plug in here.
		return npc.getWorld() == world;
	}
}
