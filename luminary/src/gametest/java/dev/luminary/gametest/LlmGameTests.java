package dev.luminary.gametest;

import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmMessage;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.fabric.LuminaryRuntime;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

import java.util.List;
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
}
