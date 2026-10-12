package dev.luminary.gametest;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.ModelDialogueProvider;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.ScriptedDialogueProvider;
import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmMessage;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** The model layer on a server without any model configured: nothing blocks, nothing throws, callers get a clean failure. */
public final class LlmGameTests implements FabricGameTest {
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
	public void noProviderFailsCleanlyOnTheServerThread(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		if (!rt.llm().provider().id().equals("none") || rt.llm().provider().healthy()) {
			throw new GameTestException("the default configuration must have no model: " + rt.llm().provider().id());
		}
		ServerCommandSource console = ctx.getWorld().getServer().getCommandSource();
		try {
			if (ctx.getWorld().getServer().getCommandManager().getDispatcher().execute("luminary llm status", console) != 1) {
				throw new GameTestException("/luminary llm status must succeed");
			}
			if (ctx.getWorld().getServer().getCommandManager().getDispatcher().execute("luminary llm ping", console) != 1) {
				throw new GameTestException("/luminary llm ping must be accepted (the failure arrives asynchronously)");
			}
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			throw new GameTestException("command syntax: " + e.getMessage());
		}
		AtomicReference<LlmException> delivered = new AtomicReference<>();
		AtomicReference<Boolean> onServerThread = new AtomicReference<>(false);
		rt.llm().ask(LlmRequest.text(List.of(LlmMessage.user("ping")), 8, 0.0), "gametest", (response, error) -> {
			delivered.set(error);
			onServerThread.set(ctx.getWorld().getServer().isOnThread());
		});
		ctx.waitAndRun(5, () -> {
			if (delivered.get() == null || delivered.get().kind() != LlmException.Kind.UNAVAILABLE) {
				throw new GameTestException("expected an UNAVAILABLE failure delivered to the callback, got " + delivered.get());
			}
			if (!onServerThread.get()) {
				throw new GameTestException("the callback must run on the server thread");
			}
			ctx.complete();
		});
	}

	/** `/luminary npc provider model` with `llm.provider = none`: the NPC still answers, from the script, without waiting. */
	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
	public void modelProviderWithoutAModelFallsBackToTheScript(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		NpcService npc = rt.npc();
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/model_fallback");
		ServerCommandSource console = ctx.getWorld().getServer().getCommandSource();
		try {
			if (ctx.getWorld().getServer().getCommandManager().getDispatcher().execute("luminary npc provider model", console) != 1) {
				throw new GameTestException("switching to the model provider must succeed even without a model");
			}
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			throw new GameTestException("command syntax: " + e.getMessage());
		}
		try {
			if (!(npc.dialogue().provider() instanceof ModelDialogueProvider)) {
				throw new GameTestException("provider must be the model provider, got " + npc.dialogue().provider().id());
			}
			Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
			if (!npc.spawn(id, ctx.getWorld(), pos).ok()) {
				throw new GameTestException("spawn failed");
			}
			LuminaryNpcEntity body = npc.body(id).orElseThrow();
			PlayerEntity player = ctx.createMockSurvivalPlayer();
			Optional<NpcDialogueService.Turn> turn = npc.talk(body, player, Optional.of("hello?")).join();
			if (turn.isEmpty() || !turn.get().fromProvider() || turn.get().literalText().isPresent()
					|| !turn.get().textKey().equals("gametest.line.model_greeting")) {
				throw new GameTestException("expected the scripted line as fallback, got " + turn);
			}
			if (!npc.dialogue().memory(id).all().stream().anyMatch(m -> m.text().contains("hello?"))) {
				throw new GameTestException("the player's words must be remembered even when the model is absent");
			}
			npc.despawn(id);
		} finally {
			rt.setDialogueProvider(new ScriptedDialogueProvider());
		}
		ctx.complete();
	}
}
