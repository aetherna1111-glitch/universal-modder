package dev.luminary.gametest;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.integration.ThaumcraftBridge;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.integration.ThaumcraftObserverEntrypoint;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** The Thaumcraft observer entrypoint, driven directly (the port is not loaded here): signals move, NPCs remember. */
public final class IntegrationGameTests implements FabricGameTest {
	@GameTest(templateName = EMPTY_STRUCTURE)
	public void thaumcraftEventsBecomeSignalsAndMemories(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		// the entrypoint is declared and resolvable the way the port resolves it: by key, typed Consumer
		boolean declared = FabricLoader.getInstance().getEntrypointContainers("thaumcraft-observer", Consumer.class).stream()
				.anyMatch(c -> c.getProvider().getMetadata().getId().equals("luminary"));
		if (!declared) {
			throw new GameTestException("luminary must declare the thaumcraft-observer entrypoint");
		}
		UUID player = UUID.nameUUIDFromBytes("gametest-thaumaturge".getBytes());
		Map<String, Object> event = new HashMap<>();
		event.put("type", "research_completed");
		event.put("player", player);
		event.put("playerName", "Thaumaturge");
		event.put("key", "GAMETEST_RESEARCH");
		event.put("warp", 0);
		Consumer<Map<String, Object>> entrypoint = new ThaumcraftObserverEntrypoint();
		LuminaryId perKey = LuminaryId.of("signal/thaumcraft/research/gametest_research");
		long before = rt.progression().state().signal(perKey);
		entrypoint.accept(event);
		entrypoint.accept(event);   // a second completion by anyone counts again
		if (rt.progression().state().signal(perKey) != before + 2) {
			throw new GameTestException("research signal must count completions, is " + rt.progression().state().signal(perKey));
		}
		boolean remembered = rt.npc().dialogue().definitions().npcs().stream().allMatch(def ->
				rt.npc().dialogue().memory(def.id()).all().stream().anyMatch(m -> m.kind() == MemoryKind.OBSERVATION
						&& m.text().contains("Thaumaturge completed the Thaumcraft research GAMETEST_RESEARCH")));
		if (!remembered) {
			throw new GameTestException("every defined NPC must remember the research as an observation");
		}
		Map<String, Object> aspect = new HashMap<>(event);
		aspect.put("type", "aspect_discovered");
		aspect.put("aspect", "Praecantatio");
		entrypoint.accept(aspect);
		entrypoint.accept(aspect);
		if (rt.progression().state().signal(LuminaryId.of("signal/thaumcraft/aspect/praecantatio")) != 1) {
			throw new GameTestException("aspect discovery is a flag, not a counter");
		}
		Map<String, Object> junk = new HashMap<>();
		junk.put("type", "research_completed");
		entrypoint.accept(junk);   // no player: ignored, no exception
		if (ThaumcraftObserverEntrypoint.apply(rt, junk).isPresent()) {
			throw new GameTestException("a malformed event must be ignored");
		}
		ctx.complete();
	}
}
