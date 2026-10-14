package dev.luminary.fabric;

import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.progression.ProgressionService;
import dev.luminary.core.story.StoryController;
import dev.luminary.core.npc.DialogueProvider;
import dev.luminary.core.npc.DisabledDialogueProvider;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.intent.IntentPlanner;
import dev.luminary.core.npc.intent.KeywordIntentPlanner;
import dev.luminary.core.npc.intent.ModelIntentPlanner;
import dev.luminary.core.npc.task.TaskRules;
import dev.luminary.core.npc.memory.NpcMemoryObserver;
import dev.luminary.core.npc.ScriptedDialogueProvider;
import dev.luminary.core.npc.ModelDialogueProvider;
import java.time.Duration;
import dev.luminary.fabric.config.LuminaryConfig;
import dev.luminary.fabric.npc.NpcService;
import dev.luminary.fabric.state.NpcWorldState;
import net.fabricmc.loader.api.FabricLoader;
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
	private final NpcWorldState npcState;
	private final EventBus events;
	private DialogueProvider dialogueProvider;
	private final LlmService llm;
	private final Duration dialogueTimeout;
	private final String intentPlannerId;
	private final LuminaryConfig config;
	private CatalogService catalog;
	private ProgressionService progression;
	private StoryController story;
	private NpcService npc;

	private LuminaryRuntime(MinecraftServer server, LuminaryWorldState worldState, NpcWorldState npcState, LuminaryConfig config) {
		this.server = server;
		this.worldState = worldState;
		this.npcState = npcState;
		this.llm = new LlmService(server, config.llm(), config.llmLogContent());
		this.dialogueTimeout = config.dialogueTimeout();
		this.intentPlannerId = config.intentPlanner();
		this.config = config;
		this.dialogueProvider = providerFor(config.dialogueProvider()).orElseGet(() -> {
			Luminary.LOGGER.warn("[luminary] unknown dialogueProvider '{}' in config; dialogue disabled", config.dialogueProvider());
			return new DisabledDialogueProvider();
		});
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
		this.story = new StoryController(worldState.state().story(), definitions.story(), progression::holds, bus, Clock.systemUTC(), worldState::markDirty);
		this.story.start();
		NpcDialogueService dialogue = new NpcDialogueService(definitions.npcs(), npcState.roster(), story, () -> progression.state().currentEra(),
				dialogueProvider, Clock.systemUTC(), npcState::markDirty, server);
		new NpcMemoryObserver(() -> definitions.npcs().npcs(), npcState.roster(), npcState::markDirty).subscribe(bus);
		this.npc = new NpcService(server, npcState, dialogue, Clock.systemUTC());
		this.npc.configureAutonomy(config.autonomyEnabled(), config.autonomy());
		this.npc.configureConversation(config.containerGrantPermission(), config.thinkingLine());
		this.npc.configureChat(config.requireName(), config.chatRange());
		this.npc.behaviour().configure(config.behaviour());
		this.npc.setPlanner(plannerFor(intentPlannerId).orElseGet(() -> {
			Luminary.LOGGER.warn("[luminary] unknown intentPlanner '{}' in config; planning disabled", intentPlannerId);
			return IntentPlanner.none();
		}));
	}

	/** Known providers by id: scripted, disabled, model (the model provider wraps the configured LLM and falls back to scripted). */
	public Optional<DialogueProvider> providerFor(String id) {
		switch (id) {
			case ScriptedDialogueProvider.ID:
				return Optional.of(new ScriptedDialogueProvider());
			case DisabledDialogueProvider.ID:
				return Optional.of(new DisabledDialogueProvider());
			case ModelDialogueProvider.ID:
				return Optional.of(new ModelDialogueProvider(llm.journaled("dialogue"), new ScriptedDialogueProvider(), dialogueTimeout,
						msg -> Luminary.LOGGER.info("[luminary] dialogue: {}", msg)));
			default:
				return Optional.empty();
		}
	}

	/** Known intent planners by id: none, keyword, model (the model planner wraps the configured LLM and falls back to keyword). */
	public Optional<IntentPlanner> plannerFor(String id) {
		switch (id) {
			case "none":
				return Optional.of(IntentPlanner.none());
			case KeywordIntentPlanner.ID:
				return Optional.of(new KeywordIntentPlanner(TaskRules.DEFAULTS));
			case ModelIntentPlanner.ID:
				return Optional.of(new ModelIntentPlanner(llm.journaled("intent"), new KeywordIntentPlanner(TaskRules.DEFAULTS), TaskRules.DEFAULTS, dialogueTimeout,
						msg -> Luminary.LOGGER.info("[luminary] {}", msg)));
			default:
				return Optional.empty();
		}
	}

	/** Flips the server-wide autonomy switch at runtime (admin command, tests); the config value returns on /reload. */
	public void setAutonomyEnabled(boolean enabled) {
		this.npc.configureAutonomy(enabled, config.autonomy());
	}

	/** Swaps the intent planner at runtime (admin command, tests). Lost on /reload (the config value returns) and restart. */
	public void setIntentPlanner(IntentPlanner planner) {
		this.npc.setPlanner(planner);
	}

	/** Swaps the dialogue provider at runtime (admin command, tests). Survives reloads until the server restarts. */
	public void setDialogueProvider(DialogueProvider provider) {
		this.dialogueProvider = provider;
		this.npc.dialogue().setProvider(provider);
	}

	private EventBus eventsRebuilt() {
		events.clearSubscriptions();
		return events;
	}

	static void start(MinecraftServer server) {
		LuminaryWorldState state = LuminaryWorldState.get(server);
		NpcWorldState npcState = NpcWorldState.get(server);
		LuminaryConfig config = LuminaryConfig.load(FabricLoader.getInstance().getConfigDir().resolve(Luminary.MOD_ID + ".json"));
		current = new LuminaryRuntime(server, state, npcState, config);
		Luminary.LOGGER.info("[luminary] state loaded: {} catalog records, {} unlocked technologies, era {}, story phase {}, {} executed effects; definitions: {} templates, {} eras, {} technologies, {} phases, {} clues",
				state.state().catalog().size(), state.state().progression().unlocked().size(), state.state().progression().currentEra().map(Object::toString).orElse("-"),
				state.state().story().currentPhase().map(Object::toString).orElse("-"),
				state.state().executedEffectIds().size(), current.catalog.templates().size(),
				current.progression.definitions().erasInOrder().size(), current.progression.definitions().technologies().size(),
				current.story.definitions().phasesInOrder().size(), current.story.definitions().clues().size());
	}

	static void stop(MinecraftServer server) {
		if (current != null && current.server == server) {
			current.worldState.markDirty();   // vanilla saves persistent states during shutdown
			current.npcState.markDirty();
			current.llm.close();
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

	public StoryController story() {
		return story;
	}

	public NpcService npc() {
		return npc;
	}

	public LlmService llm() {
		return llm;
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
