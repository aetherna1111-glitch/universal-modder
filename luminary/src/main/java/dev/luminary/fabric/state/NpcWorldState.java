package dev.luminary.fabric.state;

import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.NpcStateCodec;
import dev.luminary.core.persistence.SchemaMigrations;
import dev.luminary.fabric.Luminary;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** NPC roster and memory in {@code <world>/data/luminary_npc.dat}, separate from the civilization state (ADR-0002). */
public class NpcWorldState extends PersistentState {
	public static final String ID = Luminary.MOD_ID + "_npc";
	static final String KEY_SCHEMA = "schemaVersion";
	static final String KEY_JSON = "json";
	private static final NpcStateCodec CODEC = NpcStateCodec.production();

	private final NpcRoster roster;

	public NpcWorldState() {
		this(new NpcRoster());
	}

	public NpcWorldState(NpcRoster roster) {
		this.roster = roster;
	}

	public static NpcWorldState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(NpcWorldState::fromNbt, NpcWorldState::new, ID);
	}

	public static NpcWorldState fromNbt(NbtCompound nbt) {
		if (!nbt.contains(KEY_JSON)) {
			return new NpcWorldState();
		}
		try {
			return new NpcWorldState(CODEC.decode(nbt.getString(KEY_JSON)));
		} catch (SchemaMigrations.UnsupportedSchemaException e) {
			Luminary.LOGGER.error("[luminary] REFUSING to load NPC state: {}. NPC memory is read-only-empty until resolved.", e.getMessage());
			return new ReadOnly();
		} catch (RuntimeException e) {
			Luminary.LOGGER.error("[luminary] corrupt NPC state payload; starting empty without overwriting", e);
			return new ReadOnly();
		}
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA, NpcStateCodec.CURRENT_SCHEMA_VERSION);
		nbt.putString(KEY_JSON, CODEC.encode(roster));
		return nbt;
	}

	public NpcRoster roster() {
		return roster;
	}

	/** Returned when loading failed; mutations are never written so the file on disk is preserved. */
	public static final class ReadOnly extends NpcWorldState {
		@Override
		public void markDirty() {
		}

		@Override
		public boolean isDirty() {
			return false;
		}
	}
}
