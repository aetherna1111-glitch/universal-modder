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
	/** UTF-8 bytes of the JSON: NBT strings are capped at 65535 bytes, a growing memory is not. Legacy {@code json} is still read. */
	static final String KEY_JSON_BYTES = "jsonBytes";
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
		if (!nbt.contains(KEY_JSON) && !nbt.contains(KEY_JSON_BYTES)) {
			return new NpcWorldState();
		}
		try {
			return new NpcWorldState(CODEC.decode(readJson(nbt)));
		} catch (SchemaMigrations.UnsupportedSchemaException e) {
			Luminary.LOGGER.error("[luminary] REFUSING to load NPC state: {}. NPC memory is read-only-empty until resolved.", e.getMessage());
			return new ReadOnly();
		} catch (RuntimeException e) {
			Luminary.LOGGER.error("[luminary] corrupt NPC state payload; starting empty without overwriting", e);
			return new ReadOnly();
		}
	}

	static String readJson(NbtCompound nbt) {
		return nbt.contains(KEY_JSON_BYTES) ? new String(nbt.getByteArray(KEY_JSON_BYTES), java.nio.charset.StandardCharsets.UTF_8) : nbt.getString(KEY_JSON);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA, NpcStateCodec.CURRENT_SCHEMA_VERSION);
		nbt.putByteArray(KEY_JSON_BYTES, CODEC.encode(roster).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		nbt.remove(KEY_JSON);
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
