package dev.luminary.fabric;

import dev.luminary.fabric.command.LuminaryCommands;
import dev.luminary.fabric.definitions.DefinitionsReloadListener;
import dev.luminary.fabric.npc.NpcEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point. Wires the pure-Java core ({@code dev.luminary.core}) to the server lifecycle.
 * <p>Order on a running server: data packs load (templates) → {@code SERVER_STARTED} (state) → commands act.</p>
 */
public final class Luminary implements ModInitializer {
	public static final String MOD_ID = "luminary";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		NpcEntities.register();
		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new DefinitionsReloadListener());
		LuminaryCommands.register();
		ServerLifecycleEvents.SERVER_STARTED.register(LuminaryRuntime::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(LuminaryRuntime::stop);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> LuminaryRuntime.get().ifPresent(rt -> rt.npc().onEntityLoad(entity, world)));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> LuminaryRuntime.get().ifPresent(rt -> rt.npc().onEntityUnload(entity, world)));
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				LuminaryRuntime.get().ifPresent(rt -> rt.npc().onPlayerChat(sender, message.getSignedContent())));
		LOGGER.info("Luminary {} initialized (core schema v{})", versionString(),
				dev.luminary.core.persistence.CivilizationState.CURRENT_SCHEMA_VERSION);
	}

	private static String versionString() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}
}
