package dev.luminary.gametest;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.DisabledDialogueProvider;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.ScriptedDialogueProvider;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcEntities;
import dev.luminary.fabric.npc.NpcService;
import dev.luminary.fabric.state.NpcWorldState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;

/**
 * NPC slice on a headless server. Uses the test-only NPC {@code luminary-gametest:npc/test} (own data pack in this
 * source set) whose clue is gated by a test-only signal, so these tests never interfere with the others sharing the
 * server. Tests that need the real NPC definition use {@code luminary:npc/ascending_intelligence} read-only.
 */
public final class NpcGameTests implements FabricGameTest {
	private static final LuminaryId TEST_NPC = LuminaryId.parse("luminary-gametest:npc/test");
	private static final LuminaryId PERSIST_NPC = LuminaryId.parse("luminary-gametest:npc/persist");
	private static final LuminaryId FOLLOWER_NPC = LuminaryId.parse("luminary-gametest:npc/follower");
	private static final LuminaryId SECRET = LuminaryId.parse("luminary-gametest:clue/test_secret");
	private static final LuminaryId UNLOCK = LuminaryId.parse("luminary-gametest:signal/unlock_secret");

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void spawnIsUniquePerDefinitionAndUnknownIdsAreRefused(TestContext ctx) {
		NpcService npc = LuminaryRuntime.get().orElseThrow().npc();
		LuminaryId ghost = LuminaryId.parse("luminary-gametest:npc/ghost");
		if (npc.spawn(ghost, ctx.getWorld(), ctx.getAbsolute(new Vec3d(0.5, 1, 0.5))).ok()) {
			throw new GameTestException("spawning an undefined NPC must be refused");
		}
		LuminaryId unique = LuminaryId.parse("luminary:npc/ascending_intelligence");
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		if (!npc.spawn(unique, ctx.getWorld(), pos).ok()) {
			throw new GameTestException("first spawn must succeed");
		}
		LuminaryNpcEntity body = npc.body(unique).orElseThrow(() -> new GameTestException("roster must point at the live body"));
		if (!npc.spawn(unique, ctx.getWorld(), pos).reasonKey().equals(NpcService.REASON_ALREADY_PRESENT)) {
			throw new GameTestException("second spawn of the same NPC must be refused while the body lives");
		}
		// A duplicate body injected directly into the world (e.g. copied entity data) is discarded on load.
		LuminaryNpcEntity dup = NpcEntities.NPC.create(ctx.getWorld());
		dup.setNpcId(unique);
		dup.refreshPositionAndAngles(pos.x + 1, pos.y, pos.z, 0, 0);
		ctx.getWorld().spawnEntity(dup);
		if (!dup.isRemoved() || !npc.roster().isBody(unique, body.getUuid())) {
			throw new GameTestException("duplicate body must be discarded and the roster must keep the original");
		}
		// Despawn forgets the body but keeps the record; the NPC can be re-embodied afterwards.
		if (!npc.despawn(unique).ok() || npc.roster().get(unique).orElseThrow().entityUuid().isPresent()) {
			throw new GameTestException("despawn must mark the body missing");
		}
		if (!npc.spawn(unique, ctx.getWorld(), pos).ok()) {
			throw new GameTestException("re-spawn after despawn must succeed");
		}
		npc.despawn(unique);
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void identityAndRosterSurviveNbtRoundTrips(TestContext ctx) {
		NpcService npc = LuminaryRuntime.get().orElseThrow().npc();
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		if (!npc.spawn(PERSIST_NPC, ctx.getWorld(), pos).ok()) {
			throw new GameTestException("spawn failed");
		}
		LuminaryNpcEntity body = npc.body(PERSIST_NPC).orElseThrow();
		body.setFollowTarget(UUID.nameUUIDFromBytes("someone".getBytes()));
		// entity data: what the chunk would save and load
		NbtCompound entityNbt = new NbtCompound();
		body.writeNbt(entityNbt);
		LuminaryNpcEntity reloaded = NpcEntities.NPC.create(ctx.getWorld());
		reloaded.readNbt(entityNbt);
		if (!reloaded.npcId().equals(Optional.of(PERSIST_NPC)) || !reloaded.getUuid().equals(body.getUuid()) || reloaded.followTargetId().isEmpty()) {
			throw new GameTestException("entity NBT must carry npc id, uuid and follow target");
		}
		// roster data: what luminary_npc.dat would save and load
		NpcWorldState live = NpcWorldState.get(ctx.getWorld().getServer());
		NbtCompound stateNbt = live.writeNbt(new NbtCompound());
		NpcWorldState copy = NpcWorldState.fromNbt(stateNbt);
		NpcRoster.NpcRecord record = copy.roster().get(PERSIST_NPC).orElseThrow(() -> new GameTestException("roster record lost"));
		if (!record.entityUuid().equals(Optional.of(body.getUuid())) || record.dimension().isEmpty()) {
			throw new GameTestException("roster must remember the body uuid and dimension");
		}
		npc.despawn(PERSIST_NPC);
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void dialogueRespectsStoryGateAndDisabledProviderFallsBack(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		NpcService npc = rt.npc();
		rt.setDialogueProvider(new ScriptedDialogueProvider());
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		if (!npc.spawn(TEST_NPC, ctx.getWorld(), pos).ok()) {
			throw new GameTestException("spawn failed");
		}
		LuminaryNpcEntity body = npc.body(TEST_NPC).orElseThrow();
		PlayerEntity mock = ctx.createMockSurvivalPlayer();
		PlayerEntity player = mock;
		// 1. clue locked (signal unset): greeting
		NpcDialogueService.Turn t1 = npc.talk(body, player).orElseThrow(() -> new GameTestException("talk returned nothing"));
		if (!t1.textKey().equals("gametest.line.greeting") || rt.story().state().isRevealed(SECRET)) {
			throw new GameTestException("locked clue must not be spoken; got " + t1.textKey());
		}
		// 2. prerequisite satisfied through Progression → the NPC reveals the clue via StoryController
		rt.progression().setSignal(UNLOCK, 1, "gametest");
		NpcDialogueService.Turn t2 = npc.talk(body, player).orElseThrow();
		if (!t2.textKey().equals("gametest.clue.test_secret") || !t2.revealedClue().equals(Optional.of(SECRET)) || !rt.story().state().isRevealed(SECRET)) {
			throw new GameTestException("clue must be revealed once its condition holds; got " + t2.textKey());
		}
		// 3. revealed clues are not repeated; memory counts every meeting
		NpcDialogueService.Turn t3 = npc.talk(body, player).orElseThrow();
		if (!t3.textKey().equals("gametest.line.greeting") || t3.interactions() != 3) {
			throw new GameTestException("after the reveal the NPC returns to its lines; interactions=" + t3.interactions());
		}
		// 4. disabled provider: fallback line, no exception, meeting still remembered
		rt.setDialogueProvider(new DisabledDialogueProvider());
		NpcDialogueService.Turn t4 = npc.talk(body, player).orElseThrow();
		if (!t4.textKey().equals(NpcDialogueService.FALLBACK_TEXT_KEY) || t4.fromProvider() || t4.interactions() != 4) {
			throw new GameTestException("disabled provider must fall back safely; got " + t4.textKey());
		}
		rt.setDialogueProvider(new ScriptedDialogueProvider());
		// 5. a body whose definition does not exist talks without crashing and is reported
		LuminaryNpcEntity ghost = NpcEntities.NPC.create(ctx.getWorld());
		ghost.setNpcId(LuminaryId.parse("luminary-gametest:npc/ghost"));
		ghost.refreshPositionAndAngles(pos.x, pos.y, pos.z + 1, 0, 0);
		ctx.getWorld().spawnEntity(ghost);
		if (npc.talk(ghost, player).isPresent()) {
			throw new GameTestException("unknown definition must yield an empty turn (fallback text was sent)");
		}
		ghost.discard();
		npc.despawn(TEST_NPC);
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
	public void followOrderStartsNavigationTowardsThePlayer(TestContext ctx) {
		NpcService npc = LuminaryRuntime.get().orElseThrow().npc();
		// a stone walkway east of the (1x1) empty template so the ground pathfinder has something to walk on
		for (int x = 0; x <= 8; x++) {
			ctx.getWorld().setBlockState(ctx.getAbsolutePos(new BlockPos(x, 0, 0)), net.minecraft.block.Blocks.STONE.getDefaultState());
			ctx.getWorld().setBlockState(ctx.getAbsolutePos(new BlockPos(x, 1, 0)), net.minecraft.block.Blocks.AIR.getDefaultState());
			ctx.getWorld().setBlockState(ctx.getAbsolutePos(new BlockPos(x, 2, 0)), net.minecraft.block.Blocks.AIR.getDefaultState());
		}
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		if (!npc.spawn(FOLLOWER_NPC, ctx.getWorld(), pos).ok()) {
			throw new GameTestException("spawn failed");
		}
		LuminaryNpcEntity body = npc.body(FOLLOWER_NPC).orElseThrow();
		PlayerEntity mock = ctx.createMockSurvivalPlayer();
		Vec3d far = pos.add(6, 0, 0);
		mock.refreshPositionAndAngles(far.x, far.y, far.z, 0, 0);
		if (!ctx.getWorld().spawnEntity(mock)) {
			throw new GameTestException("mock player could not be added to the world");
		}
		PlayerEntity player = mock;
		double before = body.squaredDistanceTo(player);
		if (!npc.follow(FOLLOWER_NPC, player.getUuid()).ok()) {
			throw new GameTestException("follow order refused");
		}
		ctx.waitAndRun(100, () -> {
			boolean moving = !body.getNavigation().isIdle() || body.squaredDistanceTo(player) < before - 0.5;
			npc.despawn(FOLLOWER_NPC);
			player.discard();
			if (!moving) {
				throw new GameTestException("after a follow order the NPC must start navigating or get closer (before=" + before
						+ ", after=" + body.squaredDistanceTo(player) + ")");
			}
			ctx.complete();
		});
	}
}
