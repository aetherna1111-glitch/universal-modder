package dev.luminary.fabric.npc;

import dev.luminary.fabric.Luminary;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** Entity registration. One generic entity type; the NPC definition id is per-instance data (NBT). */
public final class NpcEntities {
	public static final Identifier NPC_ID = new Identifier(Luminary.MOD_ID, "npc");
	public static final EntityType<LuminaryNpcEntity> NPC = FabricEntityTypeBuilder.create(SpawnGroup.MISC, LuminaryNpcEntity::new)
			.dimensions(EntityDimensions.fixed(0.6f, 1.95f))
			.trackRangeBlocks(64)
			.build();

	private NpcEntities() {
	}

	public static void register() {
		Registry.register(Registries.ENTITY_TYPE, NPC_ID, NPC);
		FabricDefaultAttributeRegistry.register(NPC, PathAwareEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0));
	}
}
