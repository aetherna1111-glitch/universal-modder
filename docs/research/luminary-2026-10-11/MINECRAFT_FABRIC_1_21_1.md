# Minecraft 1.21.1 + Fabric technical reference

All signatures below were read on 2026-10-11 from the Yarn `1.21.1+build.3` Javadoc on `maven.fabricmc.net` or from
the `1.21.1` branch of `FabricMC/fabric` on GitHub (VERIFIED), unless labelled otherwise. Yarn names are used
(Fabric's default); Mojang-mapped names differ (e.g. `PersistentState` = `SavedData`).

## 1. Toolchain matrix

| Component | Value | Status |
|---|---|---|
| Minecraft | 1.21.1 | target chosen by the owner |
| Java runtime required by the game | 21 | VERIFIED (`fabric-example-mod` 1.21 branch builds with `release 21`; the game's launcher manifest requires 21) — LIKELY for the manifest detail |
| Fabric Loader | 0.19.5 is the newest for every game version (`meta.fabricmc.net/v2/versions/loader/1.21.1` and `/1.20.1`) | VERIFIED |
| Yarn | `1.21.1+build.3` (newest) | VERIFIED |
| Fabric API | `0.116.17+1.21.1` (newest on Modrinth) | VERIFIED |
| Loom | 1.13.6 … 1.17.21 run on **JVM 21**; **1.18.x requires JVM 25** (`org.gradle.jvm.version: 25` in the module metadata; a build with Loom 1.18.4 on Java 21 failed in this session with "Dependency requires at least JVM runtime version 25") | VERIFIED |
| Gradle for Loom 1.17.21 | plugin API variant `9.5.0`: Gradle **≥ 9.5** (Gradle 8.14.3 refused it) | VERIFIED |
| Gradle for Loom 1.18.4 | plugin API `9.7.0` | VERIFIED |
| Gradle used by `fabric-example-mod` (1.21 branch) | 9.7.1 wrapper, `loom_version=1.18-SNAPSHOT` | VERIFIED |
| GeckoLib | 4.9.3 for Fabric 1.21.1 (`geckolib-fabric-1.21.1-4.9.3.jar`, 2026-09-16), requires Fabric API | VERIFIED |

Practical consequence: with a Java 21 toolchain, pin **Loom 1.17.x + Gradle 9.5+**; with Java 25 available, the
example's `1.18` line works. Java 21 is what the 1.21.1 game needs at runtime regardless. Older Loom lines
(1.6–1.12) may also build 1.21.1 (LIKELY) but gain nothing.

The `fabric-example-mod` 1.21 branch keeps `org.gradle.configuration-cache=true`; Loom 1.17/1.18 support it
(LIKELY, the example and the repo's own 26.3 project use it).

## 2. Lifecycle and tick events (`fabric-lifecycle-events-v1`, VERIFIED)

```java
ServerLifecycleEvents.SERVER_STARTING   // (server)
ServerLifecycleEvents.SERVER_STARTED    // (server)        ← load Luminary world state here
ServerLifecycleEvents.SERVER_STOPPING   // (server)        ← flush state, cancel LLM requests
ServerLifecycleEvents.SERVER_STOPPED
ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS   // (player, joined)
ServerLifecycleEvents.START_DATA_PACK_RELOAD    // (server, serverResourceManager)
ServerLifecycleEvents.END_DATA_PACK_RELOAD      // (server, serverResourceManager, success)
ServerLifecycleEvents.BEFORE_SAVE / AFTER_SAVE  // (server, flush, force)
ServerTickEvents.START_SERVER_TICK / END_SERVER_TICK   // (server)
ServerTickEvents.START_WORLD_TICK / END_WORLD_TICK     // (world)
ServerPlayConnectionEvents.INIT / JOIN / DISCONNECT   // JOIN: (handler, sender, server)
```

Knowledge-base caveat (VERIFIED in `skills/.../engines/minecraft.md`): `JOIN` fires before the player is in the
player list; delay anything that needs the player list by ~10 ticks.

## 3. Commands (`fabric-command-api-v2`, VERIFIED)

```java
CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
    dispatcher.register(CommandManager.literal("luminary")
        .requires(src -> src.hasPermissionLevel(2))
        .then(CommandManager.literal("story")
            .then(CommandManager.literal("set")
                .requires(src -> src.hasPermissionLevel(4))       // admin only: narrative control
                .then(CommandManager.argument("phase", StringArgumentType.word())
                    .executes(ctx -> { /* StoryController.setPhase(...) */ return 1; })))));
});
```
`CommandManager.RegistrationEnvironment` tells dedicated vs integrated. Brigadier types (`StringArgumentType`,
`IntegerArgumentType`) are LIKELY unchanged in 1.21.1. For finer permissions than op levels, `fabric-permissions-api`
(lucko, MIT, has 1.21.1 builds — VERIFIED on Modrinth) is the de facto standard (`Permissions.check(source, "luminary.story.set", 4)` — LIKELY signature).

## 4. Persistent server data (`net.minecraft.world.PersistentState`, VERIFIED signatures)

```java
public final class LuminaryWorldState extends PersistentState {
    public static final PersistentState.Type<LuminaryWorldState> TYPE = new PersistentState.Type<>(
        LuminaryWorldState::new,                                   // Supplier<T>
        (NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) -> fromNbt(nbt),   // BiFunction<NbtCompound, WrapperLookup, T>
        null);                                                     // DataFixTypes: null = no vanilla datafixer (LIKELY accepted; vanilla passes null for some states)

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) { ...; return nbt; }
}
// obtain: server.getOverworld().getPersistentStateManager().getOrCreate(LuminaryWorldState.TYPE, "luminary");
// PersistentStateManager also has get(Type, String), set(String, PersistentState), save()
// mark changed: state.markDirty(); isDirty()
```
Files land in `<world>/data/<name>.dat`. The Kindred note (1.20.1) used the same mechanism for an owner→body roster
that survives chunk unload (VERIFIED as reported there). The `DataFixTypes` parameter is new relative to 1.20.1
(there the Type had two arguments) — LIKELY; verify when porting 1.20.1 snippets.

Recommendation (see `LUMINARY_SYSTEM_DESIGN.md` §6): keep the domain state serializable as JSON by pure-Java code and
store that JSON (plus `schemaVersion`) inside the NBT, so unit tests can round-trip without Minecraft and migrations
operate on a plain tree. Large states (NPC memory) should be split into separate `PersistentState` names or files
under `<world>/luminary/` written off-thread, because `PersistentStateManager.save()` runs on the server thread.

## 5. Networking (`fabric-networking-api-v1`, VERIFIED signatures)

```java
public record CatalogSyncPayload(String json) implements CustomPayload {
    public static final CustomPayload.Id<CatalogSyncPayload> ID = new CustomPayload.Id<>(Identifier.of("luminary", "catalog_sync"));
    public static final PacketCodec<RegistryByteBuf, CatalogSyncPayload> CODEC = PacketCodecs.STRING.xmap(CatalogSyncPayload::new, CatalogSyncPayload::json).cast();  // LIKELY: codec helper names
    @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
PayloadTypeRegistry.playS2C().register(CatalogSyncPayload.ID, CatalogSyncPayload.CODEC);
ServerPlayNetworking.send(player, new CatalogSyncPayload(json));
ServerPlayNetworking.registerGlobalReceiver(SomeC2S.ID, (payload, context) -> { /* context.player(), context.server() */ });
```
Registries: `playC2S()`, `playS2C()` (`RegistryByteBuf`), `configurationC2S()/S2C()` (`PacketByteBuf`). The old
`PacketByteBuf`-only `registerGlobalReceiver(Identifier, ...)` API is gone since 1.20.5 (LIKELY). `Identifier.of(ns,
path)` replaced the public constructor in 1.21 (LIKELY; the Javadoc lists `of`).

## 6. Entities (VERIFIED where marked)

- `FabricEntityTypeBuilder` is `@Deprecated` on the 1.21.1 branch; the replacement is vanilla
  `EntityType.Builder.create(EntityFactory<T>, SpawnGroup)` + `.build(String id)` with Fabric's `FabricEntityType`
  extension (file present on the branch) — VERIFIED existence, LIKELY exact chaining.
- Attributes: `FabricDefaultAttributeRegistry.register(type, MobEntity.createMobAttributes()...)` — VERIFIED class
  exists on 1.21.1.
- AI goals: `net.minecraft.entity.ai.goal.Goal`, `GoalSelector` with `add(priority, goal)`, `Goal.Control`
  (`MOVE`, `LOOK`, `JUMP`, `TARGET`) — LIKELY unchanged.
- Navigation: `MobEntity.getNavigation().startMovingTo(x, y, z, speed)` / `findPathTo(...)`; `EntityNavigation`
  returns null paths when unreachable — LIKELY.
- Persistence: `writeCustomDataToNbt(NbtCompound)` / `readCustomDataFromNbt(NbtCompound)` on the entity — LIKELY.
  In 1.21.1 the pattern `setPersistent()` on `MobEntity` prevents despawn — LIKELY.
- Inventory: `SimpleInventory`; item use via the actor-surrogate approach (an unconnected `ServerPlayerEntity`) is
  how Kindred got vanilla interaction hooks; it is powerful but fragile with mods that require a session
  (VERIFIED as reported in the Kindred note).

## 7. Data pack conventions (1.21, VERIFIED in the NeoForge 1.21.1 KB note)

Singular folder names: `data/<ns>/recipe/`, `loot_table/`, `structure/`, `tags/block/`, `tags/item/`. Recipe results
use `{"id": "..."}`. Reload listeners: `ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(Identifier, Function<WrapperLookup, IdentifiableResourceReloadListener>)` (VERIFIED signature) is the way to load Luminary definitions (eras, technologies, discovery templates) from data packs and re-validate them on `/reload`.

## 8. GameTest (VERIFIED)

`net.minecraft.test.GameTest` annotation fields: `templateName`, `batchId`, `tickLimit`, `required`,
`maxAttempts`, `requiredSuccesses`, `rotation`, `skyAccess`, `manualOnly`. Fabric's `FabricGameTest` interface
offers `EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty"` and `invokeTestMethod(TestContext, Method)`. Tests are
declared in `fabric.mod.json` under the `fabric-gametest` entrypoint (LIKELY key name) and run with
`-Dfabric-api.gametest` and `-Dfabric-api.gametest.report-file=<junit.xml>` — both VERIFIED in use in
`thaumcraft-fabric/build.gradle`.

## 9. Minimal `build.gradle` for a Java 21 toolchain (drafted in this session; compiled: **no**)

```groovy
plugins { id 'net.fabricmc.fabric-loom' version '1.17.21'; id 'java-library' }   // Gradle ≥ 9.5, JVM 21
dependencies {
    minecraft "com.mojang:minecraft:1.21.1"
    mappings "net.fabricmc:yarn:1.21.1+build.3:v2"
    modImplementation "net.fabricmc:fabric-loader:0.19.5"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.17+1.21.1"
    testImplementation platform('org.junit:junit-bom:5.11.4'); testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
tasks.withType(JavaCompile).configureEach { options.release = 21 }
loom.runs { gametest { server(); vmArg '-Dfabric-api.gametest'; vmArg "-Dfabric-api.gametest.report-file=${layout.buildDirectory.get().asFile}/junit.xml" } }
```
`fabric.mod.json` `depends`: `"minecraft": "~1.21.1"`, `"java": ">=21"`, `"fabricloader": ">=0.16.0"`, `"fabric-api": "*"`.
Network note for this container: declare `maven { url 'https://repo1.maven.org/maven2/' }` before `mavenCentral()`
because `repo.maven.apache.org` rate-limited (429) through the proxy.
