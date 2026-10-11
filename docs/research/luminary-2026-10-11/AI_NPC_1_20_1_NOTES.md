# AI NPC on 1.20.1 — implementation notes

`AI_NPC_TECHNICAL_RESEARCH.md` (boundary, components, typed actions, memory model, provider abstraction, Ollama
facts, threading, prototype ladder, risks) is version-independent and stands. This file gives the 1.20.1-specific API
surface and records that the Kindred precedent is now **the same stack** as Luminary's target.

## Kindred stack = Luminary stack (VERIFIED from the KB note)

Minecraft 1.20.1, Fabric Loader 0.15.11, Fabric API 0.92.2+1.20.1, Loom 1.6.12, Yarn 1.20.1+build.10, Gradle 8.7,
Java 17, Ollama at `127.0.0.1:11434`. Reported working: `LivingEntity` subclass, NBT persistence of inventory/mood/
home/12 events, `PersistentState` roster, bounded sensors, ground pathfinder throttled to ≤ 1 attempt/s, reflexes
without LLM, structured-output action turns, 19 unit tests + 17 GameTests. Its source is not in this repository;
the note is the only artifact. Everything Kindred did is therefore **known-feasible on 1.20.1**, but must be
re-implemented.

## Entity registration (VERIFIED 1.20.1 APIs)

```java
public static final EntityType<AscendingIntelligenceEntity> TYPE = Registry.register(Registries.ENTITY_TYPE,
    new Identifier("luminary", "ascending_intelligence"),
    FabricEntityTypeBuilder.create(SpawnGroup.MISC, AscendingIntelligenceEntity::new)
        .dimensions(EntityDimensions.fixed(0.6f, 1.95f)).trackRangeBlocks(64).trackedUpdateRate(2).build());
FabricDefaultAttributeRegistry.register(TYPE, PathAwareEntity.createMobAttributes()
    .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3).add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0));
```
`FabricEntityTypeBuilder` is **not** deprecated on the 1.20.1 branch (VERIFIED), unlike 1.21.1.

## Entity body (LIKELY APIs, Yarn 1.20.1)

- `class AscendingIntelligenceEntity extends PathAwareEntity` (gives `MobNavigation`, goals, `setPersistent()`).
- Goals via `initGoals()`: `goalSelector.add(0, new ExecuteTaskGoal(this))`, `add(5, new LookAtEntityGoal(...))`,
  `add(6, new WanderAroundFarGoal(...))` only when idle and a player is within range.
- Persistence: `writeCustomDataToNbt(NbtCompound)` / `readCustomDataFromNbt(NbtCompound)` for body-local state
  (current task id, inventory, task progress); identity and memory in `luminary_npc` `PersistentState`.
- Navigation: `getNavigation().startMovingTo(x, y, z, speed)`; budget `findPathTo(BlockPos, int)` ≤ 1/s; stuck
  detection by position delta over 60 ticks → task `failed("stuck")`.
- Despawn protection: `setPersistent()`; `canImmediatelyDespawn` → false; `isPersistent()`.
- Inventory: `SimpleInventory(27)`; provenance ledger per stack (origin: PICKUP/WITHDRAW/CRAFT) kept alongside.
- Block breaking: check `state.isIn(BlockTags.LOGS)` and tool `isSuitableFor(state)`; `world.breakBlock(pos, true)`
  drops items; collect via `world.getEntitiesByClass(ItemEntity.class, box, pred)` within 1.5 blocks and
  `ItemEntity.getStack()` → inventory, then `discard()` (LIKELY names).
- Container deposit: `world.getBlockEntity(pos) instanceof Inventory inv` → insert via `Inventories`/manual loop,
  only when the container position is in the NPC's allowed set.
- Milking: `CowEntity` + a bucket in hand → `ItemUsage.exchangeStack`-style swap bucket→milk_bucket done by executor
  code, not by faking a player interaction (LIKELY; avoids the actor-surrogate).
- Furnace: `AbstractFurnaceBlockEntity` slots 0 input / 1 fuel / 2 output via `Inventory.setStack` (LIKELY).

## Chat intake (LIKELY APIs)

`ServerMessageEvents.CHAT_MESSAGE` (`fabric-message-api-v1`, 1.20.1) gives `(SignedMessage, ServerPlayerEntity,
Parameters)`; filter by distance to the NPC (≤ 8 blocks) or by an explicit prefix; never consume the message.
Replies via `player.sendMessage(Text)` or an NPC-bubble packet.

## Networking for animation state (VERIFIED 1.20.1 API shape)

`TrackedData<Integer> TASK_PHASE` registered with `DataTracker.registerData(Class, TrackedDataHandlerRegistry.INTEGER)`
(LIKELY) syncs automatically; the GeckoLib 4.8.4 client controller reads it. One-shot clips: a `FabricPacket`
`NpcAnimPacket(entityId, clipId)` sent to players tracking the entity (`PlayerLookup.tracking(entity)` — VERIFIED
class exists in `fabric-networking-api-v1`, LIKELY on 1.20.1).

## LLM provider on Java 17

`java.net.http.HttpClient` (JDK 11+) with `HttpClient.newBuilder().connectTimeout(2s).followRedirects(NEVER)`;
`BodyHandlers.ofInputStream()` with a 64 KiB cap, read on a single daemon executor; results posted with
`server.execute(...)`. Ollama request body per `AI_NPC_TECHNICAL_RESEARCH.md` §5 (`format` = Intent JSON schema,
`stream:false`, `keep_alive`, `options.num_ctx/num_predict`). Endpoint allow-list defaults to loopback.

## GameTests for the NPC on 1.20.1 (VERIFIED harness; LIKELY test API)

`TestContext.spawnEntity(TYPE, pos)`, `setBlockState`, `expectBlock`, `runAtTick`, `waitAndRun`, `complete()`,
`expectEntityWithData`; structure templates in `src/gametest/resources/data/luminary-gametest/structures/*.nbt` or
`FabricGameTest.EMPTY_STRUCTURE`. Target invariants: logs in chest == logs removed; no items created; same UUID
after chunk unload/reload (Kindred proved this on 1.20.1).
