package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.task.NpcTask;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Persisted NPC identity and memory, independent of entity lifetime (chunk unload, death, deleted entity). One
 * record per {@link NpcDefinition}; the record remembers which entity UUID currently embodies the NPC so a second
 * spawn or a duplicated entity can be refused, and remembers each player it has talked to.
 */
public final class NpcRoster {
	/** What the NPC remembers about one player. {@code lastLineKey} drives deterministic rotation of equal-priority lines. */
	public record PlayerMemory(String nameAtLastMeeting, int interactions, Instant firstMet, Instant lastMet, String lastLineKey) {
		PlayerMemory met(String name, Instant at, String lineKey) {
			return new PlayerMemory(name, interactions + 1, firstMet, at, lineKey);
		}
	}

	/** One NPC's record. {@code entityUuid} is null while no entity embodies the NPC (never spawned or marked missing). */
	public static final class NpcRecord {
		private UUID entityUuid;
		private String dimension;
		private long lastPosPacked;
		private Instant lastSeen;
		private final Map<UUID, PlayerMemory> players = new TreeMap<>();
		private final Set<Long> authorizedContainers = new TreeSet<>();
		private NpcTask task;

		/** Containers (packed block positions) this NPC may deposit into. Set by administrators only. */
		public Set<Long> authorizedContainers() {
			return Collections.unmodifiableSet(authorizedContainers);
		}

		/** The current task (active) or the last one (terminal); empty when the NPC never had a task. */
		public Optional<NpcTask> task() {
			return Optional.ofNullable(task);
		}

		public boolean hasActiveTask() {
			return task != null && task.isActive();
		}

		public Optional<UUID> entityUuid() {
			return Optional.ofNullable(entityUuid);
		}

		public Optional<String> dimension() {
			return Optional.ofNullable(dimension);
		}

		public long lastPosPacked() {
			return lastPosPacked;
		}

		public Optional<Instant> lastSeen() {
			return Optional.ofNullable(lastSeen);
		}

		public Map<UUID, PlayerMemory> players() {
			return Collections.unmodifiableMap(players);
		}

		public Optional<PlayerMemory> player(UUID id) {
			return Optional.ofNullable(players.get(id));
		}
	}

	public enum RegisterResult { REGISTERED, ALREADY_THIS_ENTITY, OCCUPIED }

	private final Map<LuminaryId, NpcRecord> records = new LinkedHashMap<>();

	public Optional<NpcRecord> get(LuminaryId npc) {
		return Optional.ofNullable(records.get(npc));
	}

	public Map<LuminaryId, NpcRecord> all() {
		return Collections.unmodifiableMap(records);
	}

	private NpcRecord ensure(LuminaryId npc) {
		return records.computeIfAbsent(npc, k -> new NpcRecord());
	}

	/**
	 * Binds an entity to the NPC. Refused ({@code OCCUPIED}) while a different entity holds the record; the caller
	 * decides whether that entity still exists and, if not, calls {@link #markMissing} first.
	 */
	public RegisterResult register(LuminaryId npc, UUID entity, String dimension, long posPacked, Instant now) {
		NpcRecord r = ensure(npc);
		if (r.entityUuid != null && !r.entityUuid.equals(entity)) {
			return RegisterResult.OCCUPIED;
		}
		boolean same = entity.equals(r.entityUuid);
		r.entityUuid = entity;
		r.dimension = dimension;
		r.lastPosPacked = posPacked;
		r.lastSeen = now;
		return same ? RegisterResult.ALREADY_THIS_ENTITY : RegisterResult.REGISTERED;
	}

	/** True when {@code entity} is the registered body of {@code npc}. */
	public boolean isBody(LuminaryId npc, UUID entity) {
		NpcRecord r = records.get(npc);
		return r != null && entity.equals(r.entityUuid);
	}

	public void seen(LuminaryId npc, String dimension, long posPacked, Instant now) {
		NpcRecord r = ensure(npc);
		r.dimension = dimension;
		r.lastPosPacked = posPacked;
		r.lastSeen = now;
	}

	/** Forgets the body (entity removed, killed or lost) but keeps memory and last position. */
	public void markMissing(LuminaryId npc) {
		NpcRecord r = records.get(npc);
		if (r != null) {
			r.entityUuid = null;
		}
	}

	public void authorizeContainer(LuminaryId npc, long pos) {
		ensure(npc).authorizedContainers.add(pos);
	}

	public boolean revokeContainer(LuminaryId npc, long pos) {
		NpcRecord r = records.get(npc);
		return r != null && r.authorizedContainers.remove(pos);
	}

	/**
	 * Installs a new task. Refused while another task is active: an NPC never runs two tasks at once, and a terminal
	 * task is replaced (its ledger is still readable until then).
	 */
	public boolean startTask(LuminaryId npc, NpcTask task) {
		NpcRecord r = ensure(npc);
		if (r.task != null && r.task.isActive()) {
			return false;
		}
		r.task = task;
		return true;
	}

	public PlayerMemory remember(LuminaryId npc, UUID player, String playerName, Instant now, String lineKey) {
		NpcRecord r = ensure(npc);
		PlayerMemory m = r.players.get(player);
		m = m == null ? new PlayerMemory(playerName, 1, now, now, lineKey) : m.met(playerName, now, lineKey);
		r.players.put(player, m);
		return m;
	}

	/** Restores persisted values; codec only. */
	public static final class Restore {
		private Restore() {
		}

		public static void record(NpcRoster roster, LuminaryId npc, UUID entity, String dimension, long pos, Instant lastSeen) {
			NpcRecord r = roster.ensure(npc);
			r.entityUuid = entity;
			r.dimension = dimension;
			r.lastPosPacked = pos;
			r.lastSeen = lastSeen;
		}

		public static void player(NpcRoster roster, LuminaryId npc, UUID player, PlayerMemory memory) {
			roster.ensure(npc).players.put(player, memory);
		}

		public static void container(NpcRoster roster, LuminaryId npc, long pos) {
			roster.ensure(npc).authorizedContainers.add(pos);
		}

		public static void task(NpcRoster roster, LuminaryId npc, NpcTask task) {
			roster.ensure(npc).task = task;
		}
	}
}
