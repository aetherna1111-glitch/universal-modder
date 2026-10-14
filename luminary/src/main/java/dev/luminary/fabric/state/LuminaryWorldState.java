package dev.luminary.fabric.state;

import dev.luminary.core.persistence.CivilizationState;
import dev.luminary.core.persistence.CivilizationStateCodec;
import dev.luminary.core.persistence.SchemaMigrations;
import dev.luminary.fabric.Luminary;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/**
 * Stores the whole {@link CivilizationState} as JSON inside vanilla's persistent-state NBT
 * ({@code <world>/data/luminary.dat}). The JSON is produced by the Minecraft-free codec so unit tests cover the
 * format and migrations without a server.
 * <p>Signatures are the 1.20.1 ones: {@code writeNbt(NbtCompound)} and
 * {@code PersistentStateManager.getOrCreate(Function, Supplier, String)}.</p>
 */
public class LuminaryWorldState extends PersistentState {
	public static final String ID = Luminary.MOD_ID;
	static final String KEY_SCHEMA = "schemaVersion";
	static final String KEY_JSON = "json";
	/** UTF-8 bytes of the JSON: NBT strings are capped at 65535 bytes, a growing memory is not. Legacy {@code json} is still read. */
	static final String KEY_JSON_BYTES = "jsonBytes";

	private static final CivilizationStateCodec CODEC = CivilizationStateCodec.production();

	private final CivilizationState state;

	public LuminaryWorldState() {
		this(new CivilizationState());
	}

	public LuminaryWorldState(CivilizationState state) {
		this.state = state;
	}

	public static LuminaryWorldState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(LuminaryWorldState::fromNbt, LuminaryWorldState::new, ID);
	}

	public static LuminaryWorldState fromNbt(NbtCompound nbt) {
		if (!nbt.contains(KEY_JSON) && !nbt.contains(KEY_JSON_BYTES)) {
			Luminary.LOGGER.warn("[luminary] persistent state without payload; starting empty");
			return new LuminaryWorldState();
		}
		try {
			return new LuminaryWorldState(CODEC.decode(readJson(nbt)));
		} catch (SchemaMigrations.UnsupportedSchemaException e) {
			// Fail closed: never overwrite data from a newer Luminary. The server keeps running with an empty,
			// non-persisting view so admins can downgrade/upgrade deliberately.
			Luminary.LOGGER.error("[luminary] REFUSING to load state: {}. Catalog is read-only-empty until resolved.", e.getMessage());
			return new ReadOnlyWorldState();
		} catch (RuntimeException e) {
			Luminary.LOGGER.error("[luminary] corrupt state payload; starting empty. Raw NBT kept by vanilla until next save.", e);
			return new ReadOnlyWorldState();
		}
	}

	static String readJson(NbtCompound nbt) {
		return nbt.contains(KEY_JSON_BYTES) ? new String(nbt.getByteArray(KEY_JSON_BYTES), java.nio.charset.StandardCharsets.UTF_8) : nbt.getString(KEY_JSON);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA, CivilizationState.CURRENT_SCHEMA_VERSION);
		nbt.putByteArray(KEY_JSON_BYTES, CODEC.encode(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		nbt.remove(KEY_JSON);
		return nbt;
	}

	public CivilizationState state() {
		return state;
	}

	/** Returned when loading failed: mutations are not persisted, so the on-disk file is never clobbered. */
	public static final class ReadOnlyWorldState extends LuminaryWorldState {
		@Override
		public void markDirty() {
			// intentionally never dirty
		}

		@Override
		public boolean isDirty() {
			return false;
		}
	}
}
