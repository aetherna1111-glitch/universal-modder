# Luminary system design — 1.20.1 addendum

`LUMINARY_SYSTEM_DESIGN.md` remains the design: principles, module graph, Catalog/Progression/Story/City models,
events, persistence-as-JSON, idempotent effects, first slice. This addendum replaces its Minecraft-facing details
with 1.20.1 ones and adds what becomes possible now that the Thaumcraft port and Create are in-process.

## 1. Fabric layer on 1.20.1 (replaces §6 and §5 enforcement notes)

```java
public final class Luminary implements ModInitializer {               // dev.luminary.fabric
    @Override public void onInitialize() {
        LuminaryConfig cfg = LuminaryConfig.load(FabricLoader.getInstance().getConfigDir().resolve("luminary.json"));
        FeatureFlags flags = FeatureFlags.from(cfg);
        LuminaryCommands.register();                                     // CommandRegistrationCallback.EVENT
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LuminaryWorldState st = server.getOverworld().getPersistentStateManager()
                    .getOrCreate(LuminaryWorldState::fromNbt, LuminaryWorldState::new, "luminary");
            LuminaryRuntime.start(server, st, flags);                    // builds core services, wires DomainEvent listeners
            IntegrationRegistry.initializeAll(FabricLoader.getInstance()::isModLoaded, ctx, LOGGER);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LuminaryRuntime.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> LuminaryRuntime.tick(server));   // budgets: progression eval ≤ 1/5 s, backups
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new DefinitionsReloadListener()); // eras/tech/discoveries/story JSON
    }
}
```
- `LuminaryWorldState extends PersistentState` with `writeNbt(NbtCompound)` and a static `fromNbt(NbtCompound)`
  (VERIFIED 1.20.1 signatures). Payload: `schemaVersion` int + `json` string; migrations on the JSON tree.
- Backups: no `AFTER_SAVE` event on 1.20.1; write the rolling JSON backup from `END_SERVER_TICK` every N minutes on a
  worker thread from an immutable snapshot.
- Networking: `FabricPacket` + `PacketType` (S2C `CatalogSyncPacket`, `StoryStatePacket`; C2S only UI requests).
- Capability enforcement: `UseItemCallback`/`UseBlockCallback` deny with a message when the item/block carries tag
  `luminary:requires/<capability>` and the civilization lacks it; recipes gated by a Luminary data pack override
  (plural `data/luminary/recipes/`) plus `ServerPlayerEntity.unlockRecipes` for discovery UX (LIKELY API).

## 2. Thaumaturgy and industry as first-class inputs (new)

| Signal | Producer | Luminary consumer |
|---|---|---|
| `thaumcraft:research_completed(player, researchKey)` | port (via proposed plugin/event API) | Catalog `Evidence.External`; Progression `Signal` |
| `thaumcraft:object_scanned(player, objectId, aspects)` | port | Catalog `observe(template "thaumcraft:aspect/<a>")` — first scanner attributed |
| `thaumcraft:warp_total` (sum over players) | port | Story: anomaly pressure for Phase V |
| `create:kinetic_network_running(regionId, stress)` | Create adapter sampling `KineticBlockEntity` inside Luminarium-claimed regions | `Prerequisite.Signal` for the industrial era |
| `ftbquests:quest_completed(questId)` | FTB adapter (UNVERIFIED API) | optional `Signal` |

Each producer is an optional `ModIntegration`; with the mod absent the signal simply never fires and the related
technologies stay locked — Progression definitions must therefore offer **alternative prerequisites** where a mod is
optional (e.g. industrial era via Create *or* Tech Reborn *or* a vanilla-only facility count).

## 3. Era definitions as data (1.20.1 data pack, LIKELY folder layout)

```
data/luminary/luminary/eras/zero_meridian.json      {"id":"luminary:era/zero_meridian","order":0}
data/luminary/luminary/eras/settlement.json         {"id":"luminary:era/settlement","order":1,
   "prerequisites":[{"type":"facility","facility":"luminary:workshop","min":1},
                    {"type":"discovery_verified","discovery":"luminary:material/first_trace_ore"}]}
data/luminary/luminary/technologies/arcane_worktable.json
   {"id":"luminary:tech/arcane_worktable","era":"luminary:era/town",
    "prerequisites":[{"type":"discovery_verified","discovery":"thaumcraft:aspect/praecantatio"},
                     {"type":"signal","signal":"thaumcraft:research_completed:RESEARCH","min":3}],
    "applies":["thaumcraft:aspect/praecantatio"],"grants":["luminary:cap/thaumaturgy_basic"]}
```
Loader validates ids against registered templates and reports every error with file path; unknown `type` → load
error, not silent skip.

## 4. Story control (unchanged, restated for admins)

`/luminary story set <phase>` (op 4 / `luminary.story.set`), `/luminary story branch <containment|investigation|
exploitation>`, `/luminary story clues` (lists revealed clue ids). Auto-advance per phase is `false` by default in
JSON; the LLM layer has no reference to `StoryController` mutators.

## 5. First implementation slice on 1.20.1 (when authorized)

Same as `LUMINARY_SYSTEM_DESIGN.md` §11 with the 1.20.1 build from `BUILD_CONFIGURATION_1_20_1.md`; the first
GameTest runs `/luminary catalog observe`, saves/reloads the `PersistentState` via `TestContext`'s world and asserts
the record survived. Second slice: the Thaumcraft plugin contract (`dev.luminary.api`) and a proposal to the port's
maintainers.
