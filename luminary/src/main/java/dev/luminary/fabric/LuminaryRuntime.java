package dev.luminary.fabric;

import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.catalog.Templates;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.persistence.CivilizationState;
import dev.luminary.fabric.definitions.DefinitionsReloadListener;
import dev.luminary.fabric.state.LuminaryWorldState;
import net.minecraft.server.MinecraftServer;

import java.time.Clock;
import java.util.Optional;

/**
 * Per-server holder of the core services. One dedicated server hosts one civilization, so a single static slot is
 * enough; it is cleared on {@code SERVER_STOPPING} so the integrated client can start a new world cleanly.
 * All access is on the server thread.
 */
public final class LuminaryRuntime {
	private static LuminaryRuntime current;

	private final MinecraftServer server;
	private final LuminaryWorldState worldState;
	private final EventBus events;
	private CatalogService catalog;

	private LuminaryRuntime(MinecraftServer server, LuminaryWorldState worldState) {
		this.server = server;
		this.worldState = worldState;
		this.events = new EventBus((event, listener, error) ->
				Luminary.LOGGER.error("[luminary] listener {} failed on {}", listener.getClass().getName(), event, error));
		rebuildServices(DefinitionsReloadListener.currentTemplates());
	}

	/** Called by the reload listener after templates were replaced; records are untouched. */
	public void rebuildServices(Templates templates) {
		this.catalog = new CatalogService(worldState.state().catalog(), templates, events, Clock.systemUTC(), worldState::markDirty);
	}

	static void start(MinecraftServer server) {
		LuminaryWorldState state = LuminaryWorldState.get(server);
		current = new LuminaryRuntime(server, state);
		Luminary.LOGGER.info("[luminary] state loaded: {} catalog records, {} executed effects, {} discovery templates",
				state.state().catalog().size(), state.state().executedEffectIds().size(), current.catalog.templates().size());
	}

	static void stop(MinecraftServer server) {
		if (current != null && current.server == server) {
			current.worldState.markDirty();   // vanilla saves persistent states during shutdown
			current = null;
		}
	}

	public static Optional<LuminaryRuntime> get() {
		return Optional.ofNullable(current);
	}

	public CatalogService catalog() {
		return catalog;
	}

	public CivilizationState state() {
		return worldState.state();
	}

	public EventBus events() {
		return events;
	}

	public MinecraftServer server() {
		return server;
	}
}
