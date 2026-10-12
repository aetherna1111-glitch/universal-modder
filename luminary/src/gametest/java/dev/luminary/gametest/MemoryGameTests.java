package dev.luminary.gametest;

import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import dev.luminary.fabric.state.NpcWorldState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Chat intake and event-derived memory on the headless server, with the test-only NPC {@code npc/memory}. */
public final class MemoryGameTests implements FabricGameTest {
	private static final LuminaryId NPC = LuminaryId.parse("luminary-gametest:npc/memory");

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void nearbyChatIsHeardRememberedAndAnsweredFarChatIsNot(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		NpcService npc = rt.npc();
		Vec3d pos = ctx.getAbsolute(new Vec3d(0.5, 1, 0.5));
		if (!npc.spawn(NPC, ctx.getWorld(), pos).ok()) {
			throw new GameTestException("spawn failed");
		}
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		player.refreshPositionAndAngles(pos.x + 2, pos.y, pos.z, 0, 0);
		Optional<NpcDialogueService.Turn> heard = npc.onPlayerChat(player, "Where is the ore? " + "x".repeat(400));
		if (heard.isEmpty() || !heard.get().fromProvider()) {
			throw new GameTestException("a player 2 blocks away must be heard and answered");
		}
		MemoryStore memory = npc.dialogue().memory(NPC);
		List<MemoryEntry> said = memory.forPlayer(player.getUuid()).stream().filter(e -> e.provenance() == Provenance.PLAYER_SAID).toList();
		if (said.size() != 1 || !said.get(0).text().contains("Where is the ore?") || said.get(0).text().length() > MemoryEntry.MAX_TEXT) {
			throw new GameTestException("the utterance must be remembered once, bounded: " + said);
		}
		if (memory.forPlayer(player.getUuid()).stream().noneMatch(e -> e.kind() == MemoryKind.RELATIONSHIP)) {
			throw new GameTestException("a first meeting must be remembered as a relationship fact");
		}
		player.refreshPositionAndAngles(pos.x + 30, pos.y, pos.z, 0, 0);
		if (npc.onPlayerChat(player, "can you hear me now?").isPresent()) {
			throw new GameTestException("a player 30 blocks away must not be heard");
		}
		if (memory.forPlayer(player.getUuid()).stream().anyMatch(e -> e.text().contains("hear me now"))) {
			throw new GameTestException("far chat must not be remembered");
		}
		npc.despawn(NPC);
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void civilizationEventsBecomeMemoriesAndSurviveTheRosterRoundTrip(TestContext ctx) {
		LuminaryRuntime rt = LuminaryRuntime.get().orElseThrow();
		LuminaryId discovery = LuminaryId.parse("luminary-gametest:discovery/memory_probe");
		java.util.UUID observer = java.util.UUID.nameUUIDFromBytes("memory-observer".getBytes());
		if (!rt.catalog().observe(discovery, new Attribution(observer, "Observer", Instant.now(), "minecraft:overworld", 0L)).ok()) {
			throw new GameTestException("observe failed (template missing in the test data pack?)");
		}
		MemoryStore memory = rt.npc().dialogue().memory(NPC);
		if (memory.forPlayer(observer).stream().noneMatch(e -> e.kind() == MemoryKind.OBSERVATION && e.text().contains("memory_probe"))) {
			throw new GameTestException("the NPC must remember who first observed the discovery: " + memory.all());
		}
		NpcWorldState live = NpcWorldState.get(ctx.getWorld().getServer());
		NpcWorldState copy = NpcWorldState.fromNbt(live.writeNbt(new NbtCompound()));
		MemoryStore back = copy.roster().get(NPC).orElseThrow(() -> new GameTestException("record lost")).memory();
		if (back.forPlayer(observer).size() != memory.forPlayer(observer).size()) {
			throw new GameTestException("memories must survive the NBT round trip");
		}
		ctx.complete();
	}
}
