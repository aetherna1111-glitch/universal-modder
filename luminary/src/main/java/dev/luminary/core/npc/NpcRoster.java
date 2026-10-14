package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.autonomy.AutonomyState;
import dev.luminary.core.npc.capability.CapabilityRegistry;
import dev.luminary.core.npc.combat.CombatMachine;
import dev.luminary.core.npc.combat.CombatRules;
import dev.luminary.core.npc.combat.Knockout;
import dev.luminary.core.npc.conversation.ConversationContext;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.plan.NpcPlan;
import dev.luminary.core.npc.presence.GazeTracker;
import dev.luminary.core.npc.presence.MutterChannel;
import dev.luminary.core.npc.relationship.RelationshipLedger;
import dev.luminary.core.npc.relationship.RelationshipRules;
import dev.luminary.core.npc.routine.RoutineState;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.weapon.WeaponCustody;

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
		private final MemoryStore memory = new MemoryStore();
		private final AutonomyState autonomy = new AutonomyState();
		private final Map<UUID, ConversationContext> conversations = new TreeMap<>();
		private final RelationshipLedger relationships = new RelationshipLedger();
		private final RoutineState routine = new RoutineState();
		private final CapabilityRegistry capabilities = new CapabilityRegistry();
		private GazeTracker gaze;
		private MutterChannel mutter;
		private CombatMachine combat;
		private Knockout knockout;
		/** The raised shield's remaining points (runtime) and, once broken, when it is whole again (persisted). */
		private double shieldPoints = -1;
		private Instant shieldBrokenUntil;
		private WeaponCustody weapon;
		private NpcPlan plan;

		NpcRecord(Rules rules) {
			this.gaze = new GazeTracker(rules.gaze());
			this.mutter = new MutterChannel(rules.mutter());
			this.combat = new CombatMachine(rules.combat());
		}

		/** Re-creates the rule-bound machines with new rules, keeping their persisted state. */
		void applyRules(Rules rules) {
			this.gaze = new GazeTracker(rules.gaze());
			this.mutter = new MutterChannel(rules.mutter());
			CombatMachine fresh = new CombatMachine(rules.combat());
			fresh.restore(combat.state(), combat.opponent().orElse(null), combat.stateSince());
			this.combat = fresh;
		}

		/** Gaze reaction cooldowns (runtime only). */
		public GazeTracker gaze() {
			return gaze;
		}

		/** Whisper channel (runtime only). */
		public MutterChannel mutter() {
			return mutter;
		}

		/** Self-defence state; state, opponent and timestamp persist. */
		public CombatMachine combat() {
			return combat;
		}

		/** Present while the body is down. */
		public Optional<Knockout> knockout() {
			return Optional.ofNullable(knockout);
		}

		public void setKnockout(Knockout k) {
			this.knockout = k;
		}

		public Optional<Instant> shieldBrokenUntil() {
			return Optional.ofNullable(shieldBrokenUntil);
		}

		public boolean shieldBroken(Instant now) {
			return shieldBrokenUntil != null && now.isBefore(shieldBrokenUntil);
		}

		/** Takes {@code amount} off the shield; true when it just broke (then it is out for {@code repair}). */
		public boolean damageShield(double amount, double maxPoints, Instant now, java.time.Duration repair) {
			if (shieldBroken(now)) {
				return false;
			}
			if (shieldPoints < 0 || (shieldBrokenUntil != null && !now.isBefore(shieldBrokenUntil))) {
				shieldPoints = maxPoints;   // whole again
				shieldBrokenUntil = null;
			}
			shieldPoints -= amount;
			if (shieldPoints <= 0) {
				shieldBrokenUntil = now.plus(repair);
				shieldPoints = -1;
				return true;
			}
			return false;
		}

		public double shieldPoints(double maxPoints) {
			return shieldPoints < 0 ? maxPoints : shieldPoints;
		}

		public void setShieldBrokenUntil(Instant until) {
			this.shieldBrokenUntil = until;
			this.shieldPoints = -1;
		}

		/** The unique weapon's custody; created on first use with a fresh instance id. */
		public WeaponCustody weapon() {
			if (weapon == null) {
				weapon = new WeaponCustody(UUID.randomUUID());
			}
			return weapon;
		}

		public boolean hasWeaponRecord() {
			return weapon != null;
		}

		/** The current (or last) plan. */
		public Optional<NpcPlan> plan() {
			return Optional.ofNullable(plan);
		}

		public boolean hasActivePlan() {
			return plan != null && plan.isActive();
		}

		/** Installs a plan; refused while another plan is active. */
		public boolean startPlan(NpcPlan p) {
			if (hasActivePlan()) {
				return false;
			}
			plan = p;
			return true;
		}

		public ConversationContext conversation(UUID player) {
			return conversations.computeIfAbsent(player, k -> new ConversationContext());
		}

		public Map<UUID, ConversationContext> conversations() {
			return Collections.unmodifiableMap(conversations);
		}

		public RelationshipLedger relationships() {
			return relationships;
		}

		public RoutineState routine() {
			return routine;
		}

		public CapabilityRegistry capabilities() {
			return capabilities;
		}

		/** Self-direction switch, standing orders and budget; see {@code core.npc.autonomy}. */
		public AutonomyState autonomy() {
			return autonomy;
		}

		/** Structured memory (facts, events, promises, observations); see {@code core.npc.memory}. */
		public MemoryStore memory() {
			return memory;
		}

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

	/** Server-wide rules the per-record machines are built with; set from config before any record is used. */
	public record Rules(GazeTracker.Rules gaze, MutterChannel.Rules mutter, CombatRules combat, RelationshipRules relationship) {
		public static final Rules DEFAULTS = new Rules(GazeTracker.Rules.DEFAULTS, MutterChannel.Rules.DEFAULTS, CombatRules.DEFAULTS, RelationshipRules.DEFAULTS);
	}

	private final Map<LuminaryId, NpcRecord> records = new LinkedHashMap<>();
	private Rules rules = Rules.DEFAULTS;

	public Rules rules() {
		return rules;
	}

	/** Applies new rules to every record, existing and future, keeping persisted state. */
	public void setRules(Rules rules) {
		this.rules = java.util.Objects.requireNonNull(rules);
		records.values().forEach(r -> r.applyRules(rules));
	}

	public Optional<NpcRecord> get(LuminaryId npc) {
		return Optional.ofNullable(records.get(npc));
	}

	public Map<LuminaryId, NpcRecord> all() {
		return Collections.unmodifiableMap(records);
	}

	private NpcRecord ensure(LuminaryId npc) {
		return records.computeIfAbsent(npc, k -> new NpcRecord(rules));
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

	/** Returns the record for {@code npc}, creating it; used by services that write memory before any body exists. */
	public NpcRecord ensureRecord(LuminaryId npc) {
		return ensure(npc);
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

		public static void weapon(NpcRoster roster, LuminaryId npc, WeaponCustody.State state) {
			roster.ensure(npc).weapon = new WeaponCustody(state);
		}

		public static void plan(NpcRoster roster, LuminaryId npc, NpcPlan plan) {
			roster.ensure(npc).plan = plan;
		}
	}
}
