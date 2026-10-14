package dev.luminary.fabric.npc.intent;

import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.intent.IntentCandidates;
import dev.luminary.core.npc.task.TaskRules;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Looks around the NPC body and lists what a planner may choose from: trunk bases (a log with no log below it)
 * within the scan box, the NPC's authorized containers that are loaded and are inventories, the player's position and
 * what the NPC carries. Reads only, loaded chunks only.
 */
public final class CandidateScanner {
	/** Horizontal half-size of the scan box; vertical half-size is smaller because trunks stand on the ground. */
	public static final int SCAN_XZ = 16;
	public static final int SCAN_Y = 6;
	/** How far "this chest" may be from the player's eyes. */
	public static final double LOOK_REACH = 6.0;

	private final TaskRules rules;

	public CandidateScanner(TaskRules rules) {
		this.rules = rules;
	}

	public IntentCandidates scan(ServerWorld world, LuminaryNpcEntity npc, NpcRoster.NpcRecord record, PlayerEntity player) {
		BlockPos origin = npc.getBlockPos();
		double maxSq = rules.maxRangeBlocks() * rules.maxRangeBlocks();
		List<IntentCandidates.Spot> logs = new ArrayList<>();
		List<IntentCandidates.Spot> furnaces = new ArrayList<>();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int dx = -SCAN_XZ; dx <= SCAN_XZ; dx++) {
			for (int dz = -SCAN_XZ; dz <= SCAN_XZ; dz++) {
				int cx = (origin.getX() + dx) >> 4, cz = (origin.getZ() + dz) >> 4;
				if (!world.isChunkLoaded(cx, cz)) {
					continue;
				}
				for (int dy = -SCAN_Y; dy <= SCAN_Y; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockState state = world.getBlockState(cursor);
					if (state.hasBlockEntity() && world.getBlockEntity(cursor) instanceof AbstractFurnaceBlockEntity) {
						double d = npc.squaredDistanceTo(Vec3d.ofCenter(cursor));
						if (d <= maxSq) {
							furnaces.add(new IntentCandidates.Spot(cursor.asLong(), cursor.getX(), cursor.getY(), cursor.getZ(), d));
						}
						continue;
					}
					if (!state.isIn(BlockTags.LOGS) || world.getBlockState(cursor.down()).isIn(BlockTags.LOGS)) {
						continue;
					}
					double d = npc.squaredDistanceTo(Vec3d.ofCenter(cursor));
					if (d <= maxSq) {
						logs.add(new IntentCandidates.Spot(cursor.asLong(), cursor.getX(), cursor.getY(), cursor.getZ(), d));
					}
				}
			}
		}
		logs.sort(Comparator.comparingDouble(IntentCandidates.Spot::distanceSq));
		List<IntentCandidates.ContainerInfo> containers = new ArrayList<>();
		if (record != null) {
			for (long packed : record.authorizedContainers()) {
				BlockPos pos = BlockPos.fromLong(packed);
				if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
					continue;
				}
				BlockEntity be = world.getBlockEntity(pos);
				if (!(be instanceof Inventory inv)) {
					continue;
				}
				double d = npc.squaredDistanceTo(Vec3d.ofCenter(pos));
				if (d > maxSq) {
					continue;
				}
				containers.add(new IntentCandidates.ContainerInfo(new IntentCandidates.Spot(packed, pos.getX(), pos.getY(), pos.getZ(), d), summarize(inv)));
			}
		}
		Optional<IntentCandidates.Spot> playerPos = Optional.empty();
		Optional<IntentCandidates.Spot> lookedAt = Optional.empty();
		if (player != null && player.getWorld() == world) {
			BlockPos p = player.getBlockPos();
			playerPos = Optional.of(new IntentCandidates.Spot(p.asLong(), p.getX(), p.getY(), p.getZ(), npc.squaredDistanceTo(player)));
			lookedAt = lookedAtContainer(world, player, npc);
		}
		return new IntentCandidates(logs, containers, playerPos, summarize(npc.heldItems()), furnaces, lookedAt);
	}

	/** The inventory block the player is looking at within reach, if any (the thing "this chest" refers to). */
	public static Optional<IntentCandidates.Spot> lookedAtContainer(ServerWorld world, PlayerEntity player, LuminaryNpcEntity npc) {
		HitResult hit = player.raycast(LOOK_REACH, 1.0f, false);
		if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
			return Optional.empty();
		}
		BlockPos pos = bh.getBlockPos();
		if (!(world.getBlockEntity(pos) instanceof Inventory)) {
			return Optional.empty();
		}
		return Optional.of(new IntentCandidates.Spot(pos.asLong(), pos.getX(), pos.getY(), pos.getZ(), npc.squaredDistanceTo(Vec3d.ofCenter(pos))));
	}

	/** item id → count, the most numerous first, bounded. */
	static Map<String, Integer> summarize(Inventory inv) {
		Map<String, Integer> counts = new TreeMap<>();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) {
				counts.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
			}
		}
		Map<String, Integer> top = new LinkedHashMap<>();
		counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(IntentCandidates.MAX_ITEMS_PER_CONTAINER).forEach(e -> top.put(e.getKey(), e.getValue()));
		return top;
	}
}
