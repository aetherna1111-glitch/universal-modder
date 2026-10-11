# Minecraft 1.20.1 + Fabric technical reference (Luminary target)

Signatures were read on 2026-10-11 from the Yarn `1.20.1+build.10` Javadoc on `maven.fabricmc.net` and from the
`1.20.1` branch of `FabricMC/fabric` on GitHub (VERIFIED unless labelled). Yarn names.

## 1. Version triple

| Component | Value | Evidence |
|---|---|---|
| Minecraft | 1.20.1 | owner's target |
| Java required by the game | **17** (`javaVersion.majorVersion = 17`, runtime component `java-runtime-gamma`) | VERIFIED, `piston-meta` 1.20.1 version JSON |
| Fabric Loader | 0.19.5 (newest; `fabric.mod.json` of the port requires `>=0.16.0`) | VERIFIED |
| Yarn | `1.20.1+build.10` (newest; same as the port and Kindred) | VERIFIED |
| Fabric API | `0.92.12+1.20.1` (2026-09-01; same as the port) | VERIFIED |
| GeckoLib | 4.8.4 (`geckolib-fabric-1.20.1-4.8.4.jar`, 2026-06-20, requires Fabric API) | VERIFIED |

A Java 21 JDK can **build** a 1.20.1 mod with `options.release = 17` (the Loom run configs then launch the game on
the build JDK; Minecraft 1.20.1 runs on 17+ — LIKELY). Shipping requires only that the server JVM be ≥ 17.

## 2. Lifecycle, tick, connection events (`1.20.1` branch, VERIFIED)

`ServerLifecycleEvents`: `SERVER_STARTING, SERVER_STARTED, SERVER_STOPPING, SERVER_STOPPED, SYNC_DATA_PACK_CONTENTS,
START_DATA_PACK_RELOAD, END_DATA_PACK_RELOAD` — **no `BEFORE_SAVE`/`AFTER_SAVE` on 1.20.1** (those appear in the
1.21.1 file). For the rolling JSON backup proposed in `LUMINARY_SYSTEM_DESIGN.md` §6 use `END_SERVER_TICK` with a
tick counter or hook `/save-all` via a mixin into `MinecraftServer.save` (LIKELY), or simply write the backup every
N minutes from the tick event.
`ServerTickEvents`: `START_SERVER_TICK, END_SERVER_TICK, START_WORLD_TICK, END_WORLD_TICK`.
`ServerPlayConnectionEvents`: `INIT, JOIN, DISCONNECT` (JOIN fires before the player list contains the player — KB fact).
`CommonLifecycleEvents.TAGS_LOADED` exists (the port uses it — VERIFIED in `Thaumcraft.java`).

## 3. Commands (`fabric-command-api-v2`, VERIFIED identical signature)

`CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ...)`;
`CommandManager.literal/argument`, `ServerCommandSource.hasPermissionLevel(int)`. `fabric-permissions-api 0.3.1`
exists for 1.20.1 (VERIFIED on Modrinth).

## 4. Persistent server data (VERIFIED signatures, Yarn 1.20.1+build.10)

```java
public final class LuminaryWorldState extends PersistentState {
    public static LuminaryWorldState fromNbt(NbtCompound nbt) { ... }       // Function<NbtCompound, T>
    @Override public NbtCompound writeNbt(NbtCompound nbt) { nbt.putInt("schemaVersion", v); nbt.putString("json", json); return nbt; }
}
LuminaryWorldState state = server.getOverworld().getPersistentStateManager()
        .getOrCreate(LuminaryWorldState::fromNbt, LuminaryWorldState::new, "luminary");   // getOrCreate(Function, Supplier, String id)
state.markDirty();
```
`MinecraftServer.getOverworld()` VERIFIED; file `<world>/data/luminary.dat`. Kindred used the same mechanism on
1.20.1 for the owner→body roster (VERIFIED as reported). Note the two-argument shape vs 1.21.1's `Type` object.

## 5. Networking (VERIFIED signatures)

Two coexisting APIs on 1.20.1:

```java
// (a) channel + buffer
ServerPlayNetworking.registerGlobalReceiver(Identifier channelName, PlayChannelHandler handler); // (server, player, handler, buf, responseSender)
ServerPlayNetworking.send(ServerPlayerEntity player, Identifier channelName, PacketByteBuf buf);
// (b) typed packets
public record CatalogSyncPacket(String json) implements FabricPacket {
    public static final PacketType<CatalogSyncPacket> TYPE = PacketType.create(new Identifier("luminary", "catalog_sync"), buf -> new CatalogSyncPacket(buf.readString()));
    @Override public void write(PacketByteBuf buf) { buf.writeString(json); }
    @Override public PacketType<?> getType() { return TYPE; }
}
ServerPlayNetworking.registerGlobalReceiver(PacketType<T>, PlayPacketHandler<T>);   // (packet, player, responseSender)
ServerPlayNetworking.send(player, FabricPacket)   // LIKELY overload present alongside the Identifier form
```
`new Identifier(String namespace, String path)` is the constructor on 1.20.1 (VERIFIED). Prefer (b).

## 6. Entities (VERIFIED where marked)

```java
public static final EntityType<AscendingIntelligenceEntity> ASCENDING_INTELLIGENCE = Registry.register(
    Registries.ENTITY_TYPE, new Identifier("luminary", "ascending_intelligence"),
    FabricEntityTypeBuilder.create(SpawnGroup.MISC, AscendingIntelligenceEntity::new)       // VERIFIED: create(SpawnGroup, EntityFactory), not deprecated on 1.20.1
        .dimensions(EntityDimensions.fixed(0.6f, 1.95f)).trackRangeBlocks(64).build());
FabricDefaultAttributeRegistry.register(ASCENDING_INTELLIGENCE, PathAwareEntity.createMobAttributes()
        .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3));                              // VERIFIED register(EntityType, Builder)
```
`MobEntity`: `setPersistent()`, `getNavigation()`, `writeCustomDataToNbt(NbtCompound)`, protected `goalSelector`
(VERIFIED in Javadoc). `EntityNavigation.findPathTo(BlockPos, int distance)` VERIFIED; `startMovingTo(double x, y, z,
double speed)` LIKELY (not matched by the grep; the method exists under that name in 1.20.x). `Registries.ENTITY_TYPE`
+ `Registry.register` is the 1.20.1 registration idiom (LIKELY; the port's `ThaumcraftEntities` uses it — not
re-read).

## 7. Interaction events (VERIFIED signatures, `fabric-events-interaction-v0`)

`UseBlockCallback`: `ActionResult interact(PlayerEntity, World, Hand, BlockHitResult)`;
`UseItemCallback`: `TypedActionResult<ItemStack> interact(PlayerEntity, World, Hand)`;
`PlayerBlockBreakEvents.BEFORE`: `boolean beforeBlockBreak(World, PlayerEntity, BlockPos, BlockState, @Nullable BlockEntity)`;
`AFTER`, `CANCELED`. These are the hooks for capability gating ("you cannot use this yet") and for Catalog
observations ("first player to break block X").

## 8. Threading (VERIFIED)

`MinecraftServer extends ReentrantThreadExecutor` → `server.execute(Runnable)` marshals work to the server thread
(LIKELY exact method; `ReentrantThreadExecutor`/`ThreadExecutor` expose `execute`/`submit`). Use it to post LLM
results back. `World.isChunkLoaded(BlockPos)` and `World.breakBlock(BlockPos, boolean drop)` — LIKELY (grep did not
match the Javadoc rendering; both exist in 1.20.x).

## 9. Data packs and reload (VERIFIED/LIKELY)

`ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(IdentifiableResourceReloadListener)` —
file present on the 1.20.1 branch (VERIFIED), signature with `SimpleSynchronousResourceReloadListener` LIKELY.
Folder names plural on 1.20.1: `data/<ns>/recipes/`, `loot_tables/`, `tags/blocks/`, `tags/items/`, `advancements/`
(LIKELY). Luminary definitions: `data/<ns>/luminary/{eras,technologies,discoveries,story}/*.json`.

## 10. GameTest (VERIFIED)

`@GameTest(templateName, batchId, tickLimit, required, maxAttempts, requiredSuccesses, rotation)`;
`FabricGameTest.EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty"`; run with `-Dfabric-api.gametest` and
`-Dfabric-api.gametest.report-file`. The port's `build.gradle` shows a working 1.20.1 harness: separate `gametest`
source set registered as a second Loom mod, `runDir 'build/gametest'`. Copy it.
