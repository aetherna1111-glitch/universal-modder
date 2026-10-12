package dev.luminary.fabric;

import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.progression.ProgressionService;
import dev.luminary.core.events.EventBus;
import dev.luminary.core.persistence.CivilizationState;
import dev.luminary.fabric.definitions.Definitions;
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
	private ProgressionService progression;

	private LuminaryRuntime(MinecraftServer server, LuminaryWorldState worldState) {
		this.server = server;
		this.worldState = worldState;
		this.events = new EventBus((event, listener, error) ->
				Luminary.LOGGER.error("[luminary] listener {} failed on {}", listener.getClass().getName(), event, error));
		rebuildServices(DefinitionsReloadListener.current());
	}

	/**
	 * (Re)builds the services on top of the persisted state with the given definitions. Called at start and by the
	 * reload listener after a successful {@code /reload}; records and unlocks are untouched, rules may change.
	 * A fresh EventBus-subscription set is created each time so stale services stop receiving events.
	 */
	public void rebuildServices(Definitions definitions) {
		EventBus bus = this.eventsRebuilt();
		this.catalog = new CatalogService(worldState.state().catalog(), definitions.discoveries(), bus, Clock.systemUTC(), worldState::markDirty);
		this.progression = new ProgressionService(worldState.state().progression(), definitions.progression(), catalog, bus, Clock.systemUTC(), worldState::markDirty);
		this.progression.start();
	}

	private EventBus eventsRebuilt() {
		events.clearSubscriptions();
		return events;
	}

	static void start(MinecraftServer server) {
		LuminaryWorldState state = LuminaryWorldState.get(server);
		current = new LuminaryRuntime(server, state);
		Luminary.LOGGER.info("[luminary] state loaded: {} catalog records, {} unlocked technologies, era {}, {} executed effects; definitions: {} templates, {} eras, {} technologies",
				state.state().catalog().size(), state.state().progression().unlocked().size(), state.state().progression().currentEra().map(Object::toString).orElse("-"),
				state.state().executedEffectIds().size(), current.catalog.templates().size(),
				current.progression.definitions().erasInOrder().size(), current.progression.definitions().technologies().size());
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

	public ProgressionService progression() {
		return progression;
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
