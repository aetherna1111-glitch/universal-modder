package dev.luminary.gametest;

import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.catalog.DiscoveryRecord;
import dev.luminary.core.catalog.DiscoveryStatus;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.persistence.CivilizationState;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.state.LuminaryWorldState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

import java.time.Instant;
import java.util.UUID;

/**
 * In-world checks on a headless GameTest server: data pack templates are loaded, the runtime exists after
 * SERVER_STARTED, commands mutate the server-side state, and the persistent state round-trips through NBT.
 */
public final class CatalogGameTests implements FabricGameTest {
	private static final LuminaryId ORE = LuminaryId.parse("luminary:material/first_trace_ore");
	private static final LuminaryId FOREIGN = LuminaryId.parse("luminary:structure/foreign_record");

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void runtimeAndTemplatesAreLoaded(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow(() -> new AssertionError("runtime missing after SERVER_STARTED"));
		if (rt.catalog().templates().size() < 3) {
			throw new GameTestException("expected the 3 bundled discovery templates, got " + rt.catalog().templates().size());
		}
		if (rt.catalog().templates().get(FOREIGN).orElseThrow().dependsOn().isEmpty()) {
			throw new GameTestException("foreign_record should depend on deviation_zone");
		}
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void observeCommandRecordsAttributionAndRulesHold(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		ServerCommandSource console = ctx.getWorld().getServer().getCommandSource();
		String id = "gametest/observe_" + UUID.randomUUID().toString().substring(0, 8);
		// Unknown template: command fails (returns 0) and nothing is stored.
		int unknown = tryRun(ctx, console, "luminary catalog observe luminary:" + id);
		if (unknown != 0 || rt.state().catalog().get(LuminaryId.parse("luminary:" + id)).isPresent()) {
			throw new GameTestException("observing an unknown template must fail and store nothing");
		}
		// Known template: stored once, attributed to the console source; a re-observation becomes ONE evidence entry
		// (deterministic id per observer), so the third call is a duplicate and changes nothing.
		run(ctx, console, "luminary catalog observe " + ORE);
		run(ctx, console, "luminary catalog observe " + ORE);
		int duplicate = tryRun(ctx, console, "luminary catalog observe " + ORE);
		DiscoveryRecord r = rt.state().catalog().get(ORE).orElseThrow(() -> new AssertionError("record missing"));
		if (r.status() != DiscoveryStatus.OBSERVED || r.evidence().size() != 1 || duplicate != 0) {
			throw new GameTestException("re-observation must be stored once as evidence; got status " + r.status() + ", evidence " + r.evidence().size() + ", duplicate result " + duplicate);
		}
		// Verification refused while requirements are not met (needs 2 contributors + 8 samples).
		int verified = tryRun(ctx, console, "luminary catalog verify " + ORE);
		if (verified != 0 || rt.state().catalog().get(ORE).orElseThrow().status() != DiscoveryStatus.OBSERVED) {
			throw new GameTestException("verify must be refused before requirements are met");
		}
		// Forced verification is allowed for the console (op 4) and recorded.
		run(ctx, console, "luminary catalog verify " + ORE + " force");
		DiscoveryRecord v = rt.state().catalog().get(ORE).orElseThrow();
		if (v.status() != DiscoveryStatus.VERIFIED || !v.history().get(v.history().size() - 1).reason().startsWith("admin_override:")) {
			throw new GameTestException("forced verification must be VERIFIED with an admin_override history line");
		}
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void persistentStateRoundTripsThroughNbt(TestContext ctx) {
		LuminaryWorldState live = LuminaryWorldState.get(ctx.getWorld().getServer());
		CivilizationState state = live.state();
		UUID player = UUID.nameUUIDFromBytes("gametest".getBytes());
		LuminaryId zone = LuminaryId.parse("luminary:phenomenon/deviation_zone");
		LuminaryRuntime.get().orElseThrow().catalog().observe(zone, new Attribution(player, "GameTest", Instant.now(), "minecraft:overworld", 7L));
		state.claimEffect("gametest:effect");

		NbtCompound nbt = live.writeNbt(new NbtCompound());
		LuminaryWorldState reloaded = LuminaryWorldState.fromNbt(nbt);
		if (reloaded instanceof LuminaryWorldState.ReadOnlyWorldState) {
			throw new GameTestException("payload written by this build must load");
		}
		if (reloaded.state().catalog().get(zone).map(r -> !r.firstObserver().playerId().equals(player)).orElse(true)) {
			throw new GameTestException("observation did not survive the NBT round trip");
		}
		if (reloaded.state().claimEffect("gametest:effect")) {
			throw new GameTestException("executed effect id must survive the round trip");
		}
		if (!live.isDirty()) {
			throw new GameTestException("mutations must mark the persistent state dirty");
		}
		ctx.complete();
	}

	/** Executes a command; a syntax error is a test failure, a failed command returns 0 (sendError path). */
	private static int tryRun(TestContext ctx, ServerCommandSource source, String command) {
		try {
			return ctx.getWorld().getServer().getCommandManager().getDispatcher().execute(command, source);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			throw new GameTestException("command syntax: /" + command + " -> " + e.getMessage());
		}
	}

	private static void run(TestContext ctx, ServerCommandSource source, String command) {
		try {
			int result = ctx.getWorld().getServer().getCommandManager().getDispatcher().execute(command, source);
			if (result <= 0) {
				throw new GameTestException("command failed: /" + command);
			}
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			throw new GameTestException("command syntax: /" + command + " -> " + e.getMessage());
		}
	}
}
