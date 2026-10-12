package dev.luminary.fabric.npc;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.NpcRoster;
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
import java.util.Optional;
import java.util.UUID;

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

	public NpcService(MinecraftServer server, NpcWorldState state, NpcDialogueService dialogue, Clock clock) {
		this.server = server;
		this.state = state;
		this.dialogue = dialogue;
		this.clock = clock;
		this.executor = new TaskExecutor(TaskRules.DEFAULTS, state::markDirty);
	}

	public TaskExecutor executor() {
		return executor;
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
		NpcTask task = new NpcTask(UUID.randomUUID(), action, clock.instant());
		if (!state.roster().startTask(npc, task)) {
			return Result.fail(ActionValidator.REASON_TASK_ACTIVE);
		}
		state.markDirty();
		return Result.OK;
	}

	public Result cancelTask(LuminaryId npc) {
		Optional<NpcRoster.NpcRecord> record = state.roster().get(npc);
		if (record.isEmpty() || !record.get().hasActiveTask()) {
			return Result.fail("luminary.task.none_active");
		}
		record.get().task().orElseThrow().fail(NpcTask.FAIL_CANCELLED);
		body(npc).ifPresent(b -> b.getNavigation().stop());
		state.markDirty();
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
				executor.tick(body, record);
			}
		});
	}

	public NpcDialogueService dialogue() {
		return dialogue;
	}

	public NpcRoster roster() {
		return state.roster();
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
	public java.util.concurrent.CompletableFuture<Optional<NpcDialogueService.Turn>> onPlayerChat(PlayerEntity player, String text) {
		if (!(player.getWorld() instanceof ServerWorld world) || text == null || text.isBlank()) {
			return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
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
			return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
		}
		return talk(nearest, player, Optional.of(text));
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
