package dev.luminary.fabric.npc;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.autonomy.AutonomyPlanner;
import dev.luminary.core.npc.autonomy.AutonomyRules;
import dev.luminary.core.npc.autonomy.AutonomyState;
import dev.luminary.core.npc.intent.Intent;
import dev.luminary.core.npc.intent.IntentCandidates;
import dev.luminary.core.npc.intent.IntentPlanner;
import dev.luminary.core.npc.intent.IntentRequest;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.MemoryRetriever;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.fabric.npc.intent.CandidateScanner;
import dev.luminary.core.npc.task.ActionContext;
import dev.luminary.core.npc.task.ActionValidator;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskRules;
import dev.luminary.fabric.npc.task.TaskExecutor;
import dev.luminary.fabric.npc.task.WorldActionContext;
import dev.luminary.fabric.Luminary;
import dev.luminary.fabric.state.NpcWorldState;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges NPC entities and the roster: spawning (one body per definition), reconciliation when entities load
 * (duplicates are discarded, stale bodies forgotten), position bookkeeping, follow orders and conversations.
 * Server thread only.
 */
public final class NpcService {
	public static final String REASON_UNKNOWN_NPC = "luminary.npc.unknown";
	public static final String REASON_ALREADY_PRESENT = "luminary.npc.already_present";
	public static final String REASON_NOT_PRESENT = "luminary.npc.not_present";
	public static final String REASON_SPAWN_FAILED = "luminary.npc.spawn_failed";

	private final MinecraftServer server;
	private final NpcWorldState state;
	private final NpcDialogueService dialogue;
	private final Clock clock;
	private final TaskExecutor executor;
	private final CandidateScanner scanner;
	private IntentPlanner planner = IntentPlanner.none();
	private AutonomyPlanner autonomy = new AutonomyPlanner(AutonomyRules.DEFAULTS, TaskRules.DEFAULTS);
	/** Server-wide feature flag (config {@code autonomy.enabled}); each NPC also has its own switch. */
	private boolean autonomyEnabled;

	public NpcService(MinecraftServer server, NpcWorldState state, NpcDialogueService dialogue, Clock clock) {
		this.server = server;
		this.state = state;
		this.dialogue = dialogue;
		this.clock = clock;
		this.executor = new TaskExecutor(TaskRules.DEFAULTS, state::markDirty);
		this.scanner = new CandidateScanner(TaskRules.DEFAULTS);
	}

	public TaskExecutor executor() {
		return executor;
	}

	public IntentPlanner planner() {
		return planner;
	}

	public AutonomyPlanner autonomy() {
		return autonomy;
	}

	public boolean autonomyEnabled() {
		return autonomyEnabled;
	}

	public void configureAutonomy(boolean enabled, AutonomyRules rules) {
		this.autonomyEnabled = enabled;
		this.autonomy = new AutonomyPlanner(rules, TaskRules.DEFAULTS);
	}

	/**
	 * Self-direction, called by the body every tick and acting every {@code thinkIntervalTicks}: scan, decide
	 * (reflexes, standing orders, budget), assign through the same validated path as commands. A refused target is
	 * avoided for the failure cooldown; a started task counts against the budget and is remembered as the NPC's own
	 * decision. Returns the decision taken this tick, if the planner ran.
	 */
	public Optional<AutonomyPlanner.Decision> tickAutonomy(LuminaryNpcEntity body) {
		if (!autonomyEnabled || body.npcId().isEmpty() || body.age % autonomy.rules().thinkIntervalTicks() != 0 || !(body.getWorld() instanceof ServerWorld world)) {
			return Optional.empty();
		}
		LuminaryId id = body.npcId().get();
		if (!state.roster().isBody(id, body.getUuid()) || dialogue.definitions().npc(id).isEmpty()) {
			return Optional.empty();
		}
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		AutonomyState auto = record.autonomy();
		if (!auto.enabled()) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		auto.prune(now);
		IntentCandidates seen = scanner.scan(world, body, record, null);
		AutonomyPlanner.Decision decision = autonomy.decide(auto, seen, record.hasActiveTask(), now);
		if (decision.action().isEmpty()) {
			return Optional.of(decision);
		}
		NpcAction action = decision.action().get();
		auto.recordDecision(now, autonomy.rules().window());
		Result r = assign(id, action, null);
		if (r.ok()) {
			dialogue.memory(id).add(MemoryEntry.of(MemoryKind.SIGNIFICANT_EVENT, null, "task/" + record.task().orElseThrow().id(),
					"Decided on my own (" + decision.reason() + "): " + describe(action), now, Provenance.OBSERVED_IN_WORLD), now);
			Luminary.LOGGER.info("[luminary] {} decided on its own ({}): {}", id, decision.reason(), describe(action));
		} else {
			Instant until = now.plus(autonomy.rules().failureCooldown());
			targetsOf(action).forEach(pos -> auto.avoid(pos, until));
			Luminary.LOGGER.info("[luminary] {} wanted to {} ({}) but validation said {}; avoiding the target until {}", id, describe(action), decision.reason(), r.reasonKey(), until);
		}
		state.markDirty();
		return Optional.of(decision);
	}

	private static List<Long> targetsOf(NpcAction action) {
		if (action instanceof NpcAction.GatherWood g) return List.of(g.targetPos(), g.containerPos());
		if (action instanceof NpcAction.Deposit d) return List.of(d.containerPos());
		if (action instanceof NpcAction.Withdraw w) return List.of(w.containerPos());
		if (action instanceof NpcAction.MoveTo m) return List.of(m.targetPos());
		if (action instanceof NpcAction.Smelt s) return List.of(s.furnacePos(), s.containerPos());
		return List.of();
	}

	public void setPlanner(IntentPlanner planner) {
		this.planner = java.util.Objects.requireNonNull(planner);
	}

	// ------------------------------------------------------------------ tasks

	public void authorizeContainer(LuminaryId npc, long pos) {
		state.roster().authorizeContainer(npc, pos);
		state.markDirty();
	}

	public boolean revokeContainer(LuminaryId npc, long pos) {
		boolean removed = state.roster().revokeContainer(npc, pos);
		state.markDirty();
		return removed;
	}

	/**
	 * Validates and installs a task. Everything the executor will later do was checked here against the live world;
	 * the executor re-checks on every step. Untrusted callers (commands, future planners) only ever reach this method.
	 */
	public Result assign(LuminaryId npc, NpcAction action) {
		return assign(npc, action, null);
	}

	/** As {@link #assign(LuminaryId, NpcAction)}; {@code requestedBy} is the player whose words created the task (remembered as a promise). */
	public Result assign(LuminaryId npc, NpcAction action, UUID requestedBy) {
		if (dialogue.definitions().npc(npc).isEmpty()) {
			return Result.fail(REASON_UNKNOWN_NPC);
		}
		Optional<LuminaryNpcEntity> body = body(npc);
		if (body.isEmpty()) {
			return Result.fail(ActionValidator.REASON_NO_BODY);
		}
		NpcRoster.NpcRecord record = state.roster().get(npc).orElseThrow();
		if (record.hasActiveTask()) {
			return Result.fail(ActionValidator.REASON_TASK_ACTIVE);
		}
		ActionContext ctx = new WorldActionContext((ServerWorld) body.get().getWorld(), body.get(), record);
		Result validation = ActionValidator.validateAll(executor.validators(), action, ctx);
		if (!validation.ok()) {
			return validation;
		}
		NpcTask task = new NpcTask(UUID.randomUUID(), action, clock.instant(), requestedBy);
		if (!state.roster().startTask(npc, task)) {
			return Result.fail(ActionValidator.REASON_TASK_ACTIVE);
		}
		if (requestedBy != null) {
			Instant now = clock.instant();
			dialogue.memory(npc).add(MemoryEntry.of(MemoryKind.PROMISE, requestedBy, "task/" + task.id(),
					"Promised " + nameOf(npc, requestedBy) + ": " + describe(action), now, Provenance.OBSERVED_IN_WORLD), now);
		}
		state.markDirty();
		return Result.OK;
	}

	/** Human-readable action summary for memory, chat and commands. */
	public static String describe(NpcAction action) {
		if (action instanceof NpcAction.GatherWood g) {
			return "gather " + g.maxLogs() + " logs at " + BlockPos.fromLong(g.targetPos()).toShortString() + " into the container at " + BlockPos.fromLong(g.containerPos()).toShortString();
		}
		if (action instanceof NpcAction.MoveTo m) {
			return "go to " + BlockPos.fromLong(m.targetPos()).toShortString();
		}
		if (action instanceof NpcAction.Deposit d) {
			return "deposit everything into the container at " + BlockPos.fromLong(d.containerPos()).toShortString();
		}
		if (action instanceof NpcAction.Withdraw w) {
			return "take " + w.count() + " " + w.itemId() + " from the container at " + BlockPos.fromLong(w.containerPos()).toShortString();
		}
		if (action instanceof NpcAction.Smelt s) {
			return "smelt " + s.count() + " " + s.inputItemId() + " in the furnace at " + BlockPos.fromLong(s.furnacePos()).toShortString()
					+ " using the container at " + BlockPos.fromLong(s.containerPos()).toShortString();
		}
		return action.type();
	}

	private String nameOf(LuminaryId npc, UUID player) {
		var online = server.getPlayerManager().getPlayer(player);
		if (online != null) {
			return online.getGameProfile().getName();
		}
		return state.roster().get(npc).flatMap(r -> r.player(player)).map(NpcRoster.PlayerMemory::nameAtLastMeeting).orElse("someone");
	}

	/** A task reached a terminal state: the NPC remembers the outcome (replacing the promise) and tells the requester. */
	private void onTaskFinished(LuminaryId npc, NpcTask task) {
		Instant now = clock.instant();
		String outcome = task.state() == dev.luminary.core.npc.task.TaskState.COMPLETED ? "Completed" : "Failed (" + task.failureReason().orElse("?") + ")";
		String ledger = task.ledger().blocksBroken() > 0 || task.ledger().collectedTotal() > 0 || task.ledger().depositedTotal() > 0 || task.ledger().withdrawnTotal() > 0 || task.ledger().smeltedTotal() > 0
				? " [broken " + task.ledger().blocksBroken() + ", collected " + task.ledger().collectedTotal() + ", deposited " + task.ledger().depositedTotal()
				+ ", withdrawn " + task.ledger().withdrawnTotal() + ", smelted " + task.ledger().smeltedTotal() + "]" : "";
		UUID requester = task.requestedBy().orElse(null);
		String who = requester != null ? " for " + nameOf(npc, requester) : "";
		dialogue.memory(npc).add(MemoryEntry.of(MemoryKind.SIGNIFICANT_EVENT, requester, "task/" + task.id(),
				outcome + ": " + describe(task.action()) + who + ledger, now, Provenance.OBSERVED_IN_WORLD), now);
		if (task.state() == dev.luminary.core.npc.task.TaskState.FAILED && task.requestedBy().isEmpty()) {
			// an autonomous task that failed: leave its targets alone for a while instead of retrying every think tick
			Instant until = now.plus(autonomy.rules().failureCooldown());
			state.roster().get(npc).ifPresent(r -> targetsOf(task.action()).forEach(pos -> r.autonomy().avoid(pos, until)));
		}
		if (requester != null) {
			var player = server.getPlayerManager().getPlayer(requester);
			if (player != null) {
				Text name = body(npc).map(b -> b.getCustomName() != null ? b.getCustomName() : Text.literal(npc.toString())).orElse(Text.literal(npc.toString()));
				Text line = task.state() == dev.luminary.core.npc.task.TaskState.COMPLETED
						? Text.translatable("npc.luminary.task.done", describe(task.action()))
						: Text.translatable("npc.luminary.task.failed", describe(task.action()), Text.translatable(reasonKey(task.failureReason().orElse(""))));
				player.sendMessage(Text.empty().append(Text.literal("<").append(name).append("> ").formatted(Formatting.GOLD)).append(line), false);
			}
		}
		state.markDirty();
	}

	private static String reasonKey(String failure) {
		return failure.startsWith("luminary.") ? failure : "luminary.task.fail." + failure;
	}

	public Result cancelTask(LuminaryId npc) {
		Optional<NpcRoster.NpcRecord> record = state.roster().get(npc);
		if (record.isEmpty() || !record.get().hasActiveTask()) {
			return Result.fail("luminary.task.none_active");
		}
		NpcTask task = record.get().task().orElseThrow();
		task.fail(NpcTask.FAIL_CANCELLED);
		body(npc).ifPresent(b -> b.getNavigation().stop());
		onTaskFinished(npc, task);
		return Result.OK;
	}

	/** Called every server tick by the body. No-op without an active task. */
	public void tickTask(LuminaryNpcEntity body) {
		if (body.npcId().isEmpty()) {
			return;
		}
		LuminaryId id = body.npcId().get();
		if (!state.roster().isBody(id, body.getUuid())) {
			return;   // a duplicate body awaiting discard must never execute the task
		}
		state.roster().get(id).ifPresent(record -> {
			if (record.hasActiveTask()) {
				NpcTask task = record.task().orElseThrow();
				executor.tick(body, record);
				if (!task.isActive()) {
					onTaskFinished(id, task);
				}
			}
		});
	}

	public NpcDialogueService dialogue() {
		return dialogue;
	}

	public NpcRoster roster() {
		return state.roster();
	}

	/** Every defined NPC remembers an observed fact (integrations, admins); provenance OBSERVED_IN_WORLD. */
	public void observeForAll(MemoryKind kind, UUID player, String topic, String text) {
		Instant now = clock.instant();
		for (NpcDefinition def : dialogue.definitions().npcs()) {
			dialogue.memory(def.id()).add(MemoryEntry.of(kind, player, topic, text, now, Provenance.OBSERVED_IN_WORLD), now);
		}
		state.markDirty();
	}

	/** Persist roster/memory changes made directly by commands. */
	public void markDirty() {
		state.markDirty();
	}

	// ------------------------------------------------------------------ bodies

	/** The living body of {@code npc} in any loaded world, if the roster's entity exists. */
	public Optional<LuminaryNpcEntity> body(LuminaryId npc) {
		Optional<UUID> uuid = state.roster().get(npc).flatMap(NpcRoster.NpcRecord::entityUuid);
		if (uuid.isEmpty()) {
			return Optional.empty();
		}
		for (ServerWorld world : server.getWorlds()) {
			Entity e = world.getEntity(uuid.get());
			if (e instanceof LuminaryNpcEntity npcEntity && !npcEntity.isRemoved()) {
				return Optional.of(npcEntity);
			}
		}
		return Optional.empty();
	}

	public Result spawn(LuminaryId npc, ServerWorld world, Vec3d pos) {
		Optional<NpcDefinition> def = dialogue.definitions().npc(npc);
		if (def.isEmpty()) {
			return Result.fail(REASON_UNKNOWN_NPC);
		}
		if (body(npc).isPresent()) {
			return Result.fail(REASON_ALREADY_PRESENT);
		}
		state.roster().markMissing(npc);   // whatever the record pointed at no longer exists in a loaded world
		LuminaryNpcEntity entity = NpcEntities.NPC.create(world);
		if (entity == null) {
			return Result.fail(REASON_SPAWN_FAILED);
		}
		entity.setNpcId(npc);
		entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, world.getRandom().nextFloat() * 360f, 0f);
		entity.setCustomName(Text.translatable(def.get().nameKey()));
		entity.setCustomNameVisible(true);
		if (!world.spawnEntity(entity)) {
			return Result.fail(REASON_SPAWN_FAILED);
		}
		// ENTITY_LOAD already registered it; make sure even if the event order ever changes.
		state.roster().register(npc, entity.getUuid(), dimension(world), entity.getBlockPos().asLong(), clock.instant());
		state.markDirty();
		return Result.OK;
	}

	public Result despawn(LuminaryId npc) {
		Optional<LuminaryNpcEntity> body = body(npc);
		if (body.isEmpty()) {
			return Result.fail(REASON_NOT_PRESENT);
		}
		body.get().discard();   // remove(DISCARDED) → onBodyDestroyed → markMissing
		state.roster().markMissing(npc);
		state.markDirty();
		return Result.OK;
	}

	public Result follow(LuminaryId npc, UUID player) {
		Optional<LuminaryNpcEntity> body = body(npc);
		if (body.isEmpty()) {
			return Result.fail(REASON_NOT_PRESENT);
		}
		body.get().setFollowTarget(player);
		return Result.OK;
	}

	// ------------------------------------------------------------------ lifecycle hooks

	/** Called from {@code ServerEntityEvents.ENTITY_LOAD}: binds or rejects a body. */
	public void onEntityLoad(Entity entity, ServerWorld world) {
		if (!(entity instanceof LuminaryNpcEntity npc) || npc.npcId().isEmpty()) {
			return;
		}
		LuminaryId id = npc.npcId().get();
		if (dialogue.definitions().npc(id).isEmpty()) {
			Luminary.LOGGER.warn("[luminary] NPC body {} refers to unknown definition {}; it stays but cannot talk", npc.getUuid(), id);
		}
		NpcRoster.RegisterResult result = state.roster().register(id, npc.getUuid(), dimension(world), npc.getBlockPos().asLong(), clock.instant());
		if (result == NpcRoster.RegisterResult.OCCUPIED) {
			if (body(id).isPresent()) {
				Luminary.LOGGER.warn("[luminary] duplicate body {} for NPC {} discarded (registered body is alive)", npc.getUuid(), id);
				npc.discard();
				return;
			}
			state.roster().markMissing(id);
			state.roster().register(id, npc.getUuid(), dimension(world), npc.getBlockPos().asLong(), clock.instant());
			Luminary.LOGGER.info("[luminary] NPC {} re-embodied by {} (previous body missing)", id, npc.getUuid());
		}
		state.markDirty();
	}

	/** Called from {@code ServerEntityEvents.ENTITY_UNLOAD}: remembers where the body was. */
	public void onEntityUnload(Entity entity, ServerWorld world) {
		if (entity instanceof LuminaryNpcEntity npc && npc.npcId().isPresent() && state.roster().isBody(npc.npcId().get(), npc.getUuid())) {
			state.roster().seen(npc.npcId().get(), dimension(world), npc.getBlockPos().asLong(), clock.instant());
			state.markDirty();
		}
	}

	/** Called when a body is killed or discarded (not on chunk unload). */
	void onBodyDestroyed(LuminaryNpcEntity npc) {
		if (npc.npcId().isPresent() && state.roster().isBody(npc.npcId().get(), npc.getUuid())) {
			state.roster().markMissing(npc.npcId().get());
			state.markDirty();
		}
	}

	// ------------------------------------------------------------------ conversation

	public static final double CHAT_RANGE = 8.0;

	/**
	 * Chat intake: the nearest NPC body within {@link #CHAT_RANGE} blocks of the player hears the message and answers
	 * that player. The chat message itself is never consumed or altered. Returns the turn when an NPC answered.
	 */
	public CompletableFuture<ChatOutcome> onPlayerChat(PlayerEntity player, String text) {
		if (!(player.getWorld() instanceof ServerWorld world) || text == null || text.isBlank()) {
			return CompletableFuture.completedFuture(ChatOutcome.NONE);
		}
		LuminaryNpcEntity nearest = null;
		double best = CHAT_RANGE * CHAT_RANGE;
		for (LuminaryNpcEntity npc : world.getEntitiesByClass(LuminaryNpcEntity.class, player.getBoundingBox().expand(CHAT_RANGE), e -> e.npcId().isPresent())) {
			double d = npc.squaredDistanceTo(player);
			if (d <= best && state.roster().isBody(npc.npcId().get(), npc.getUuid())) {
				best = d;
				nearest = npc;
			}
		}
		if (nearest == null) {
			return CompletableFuture.completedFuture(ChatOutcome.NONE);
		}
		return request(nearest, player, text);
	}

	/** What happened to one chat line: an intent (maybe with an assignment result) and/or a dialogue turn. */
	public record ChatOutcome(Optional<Intent> intent, Optional<Result> assignment, Optional<NpcDialogueService.Turn> turn) {
		public static final ChatOutcome NONE = new ChatOutcome(Optional.empty(), Optional.empty(), Optional.empty());

		public boolean heard() {
			return intent.isPresent() || turn.isPresent();
		}
	}

	/**
	 * A player's words to an NPC: the planner looks for work first (among what the scanner saw around the body);
	 * an action goes through {@link #assign} like any command and the NPC answers with the result; when the words
	 * were not a request for work the line becomes an ordinary dialogue turn. Everything game-side runs on the server
	 * thread; the planner may complete later (model) or at once (keyword rules).
	 */
	public CompletableFuture<ChatOutcome> request(LuminaryNpcEntity npc, PlayerEntity player, String text) {
		if (npc.npcId().isEmpty() || !(npc.getWorld() instanceof ServerWorld world)) {
			return CompletableFuture.completedFuture(ChatOutcome.NONE);
		}
		LuminaryId id = npc.npcId().get();
		Optional<NpcDefinition> def = dialogue.definitions().npc(id);
		if (def.isEmpty()) {
			return talk(npc, player, Optional.of(text)).thenApply(turn -> new ChatOutcome(Optional.empty(), Optional.empty(), turn));
		}
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		IntentCandidates candidates = scanner.scan(world, npc, record, player);
		MemoryStore memory = dialogue.memory(id);
		List<MemoryEntry> relevant = MemoryRetriever.select(memory, new MemoryRetriever.Query(player.getUuid(), MemoryRetriever.keywords(text), 8, 800), clock.instant());
		IntentRequest request = new IntentRequest(id, player.getUuid(), player.getGameProfile().getName(), text, def.get().persona(), relevant, candidates, record.hasActiveTask());
		CompletableFuture<Optional<Intent>> planned;
		try {
			planned = planner.plan(request);
		} catch (RuntimeException e) {
			Luminary.LOGGER.warn("[luminary] intent planner {} failed: {}", planner.id(), e.toString());
			planned = CompletableFuture.completedFuture(Optional.empty());
		}
		CompletableFuture<ChatOutcome> result = new CompletableFuture<>();
		planned.whenComplete((intent, error) -> {
			Optional<Intent> safe = error == null && intent != null ? intent : Optional.empty();
			server.execute(() -> {
				try {
					if (safe.isEmpty()) {
						talk(npc, player, Optional.of(text)).whenComplete((turn, e2) ->
								result.complete(new ChatOutcome(Optional.empty(), Optional.empty(), e2 == null && turn != null ? turn : Optional.empty())));
						return;
					}
					result.complete(carryOut(npc, id, player, text, safe.get()));
				} catch (RuntimeException e) {
					Luminary.LOGGER.warn("[luminary] intent handling failed for {}: {}", id, e.toString());
					result.complete(ChatOutcome.NONE);
				}
			});
		});
		return result;
	}

	/** Server thread: remember the words, assign if there is an action, answer. */
	private ChatOutcome carryOut(LuminaryNpcEntity npc, LuminaryId id, PlayerEntity player, String text, Intent intent) {
		Instant now = clock.instant();
		MemoryStore memory = dialogue.memory(id);
		String name = player.getGameProfile().getName();
		memory.add(MemoryEntry.of(MemoryKind.RECENT_INTERACTION, player.getUuid(), null, name + " asked: " + (text.length() > 200 ? text.substring(0, 200) : text), now, Provenance.PLAYER_SAID), now);
		state.roster().remember(id, player.getUuid(), name, now, intent.replyKey().orElse(DialogueResponseKeys.INTENT));
		Optional<Result> assignment = Optional.empty();
		if (intent.action().isPresent()) {
			assignment = Optional.of(assign(id, intent.action().get(), player.getUuid()));
		}
		Text npcName = npc.getCustomName() != null ? npc.getCustomName() : Text.literal(id.toString());
		Text line;
		if (assignment.isPresent() && !assignment.get().ok()) {
			line = Text.translatable("npc.luminary.intent.cannot", Text.translatable(assignment.get().reasonKey()));
		} else if (intent.literalReply().isPresent()) {
			line = Text.literal(intent.literalReply().get());
		} else {
			line = Text.translatable(intent.replyKey().orElse(Intent.REPLY_WILL_DO));
		}
		player.sendMessage(Text.empty().append(Text.literal("<").append(npcName).append("> ").formatted(Formatting.GOLD)).append(line), false);
		state.markDirty();
		return new ChatOutcome(Optional.of(intent), assignment, Optional.empty());
	}

	private static final class DialogueResponseKeys {
		static final String INTENT = "npc.luminary.intent.will_do";
	}

	/**
	 * One conversation turn; the line goes to the player's chat as "<NPC name> line" when the answer is ready
	 * (immediately for scripted dialogue, later for a model). Never throws; the future never fails.
	 */
	public java.util.concurrent.CompletableFuture<Optional<NpcDialogueService.Turn>> talk(LuminaryNpcEntity npc, PlayerEntity player) {
		return talk(npc, player, Optional.empty());
	}

	public java.util.concurrent.CompletableFuture<Optional<NpcDialogueService.Turn>> talk(LuminaryNpcEntity npc, PlayerEntity player, Optional<String> utterance) {
		if (npc.npcId().isEmpty()) {
			return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
		}
		LuminaryId id = npc.npcId().get();
		Text name = npc.getCustomName() != null ? npc.getCustomName() : Text.literal(id.toString());
		return dialogue.talk(id, player.getUuid(), player.getGameProfile().getName(), utterance).thenApply(turn -> {
			Text line = turn.flatMap(NpcDialogueService.Turn::literalText).map(t -> (Text) Text.literal(t))
					.orElseGet(() -> Text.translatable(turn.map(NpcDialogueService.Turn::textKey).orElse(NpcDialogueService.FALLBACK_TEXT_KEY)));
			player.sendMessage(Text.empty().append(Text.literal("<").append(name).append("> ").formatted(Formatting.GOLD)).append(line), false);
			if (turn.isEmpty()) {
				Luminary.LOGGER.warn("[luminary] player {} talked to NPC body {} with unknown definition {}", player.getGameProfile().getName(), npc.getUuid(), id);
			}
			state.markDirty();
			return turn;
		});
	}

	private static String dimension(ServerWorld world) {
		return world.getRegistryKey().getValue().toString();
	}

	/** Position helper for commands. */
	public static Vec3d center(BlockPos pos) {
		return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}
}
