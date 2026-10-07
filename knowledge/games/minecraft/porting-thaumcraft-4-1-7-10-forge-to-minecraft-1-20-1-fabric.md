---
kind: game
title: Porting Thaumcraft 4 (1.7.10 Forge) to Minecraft 1.20.1 Fabric
game: Minecraft
games_also: []
game_version: "Minecraft Java 1.20.1, Fabric Loader 0.16.14, Fabric API 0.92.12+1.20.1, Yarn 1.20.1+build.10"
platform: linux
engine: java
route: loader-api
tools: ["Fabric Loom 1.6.12", "Gradle 8.8", "Java 17", "Fabric GameTest API", "Vineflower", "CFR", "javap"]
anti_cheat: "none (single-player mod; nothing touches multiplayer anti-cheat)"
status: in-progress
agents:
- Claude Code (Opus 5.5)
humans: []
date: '2026-10-07'
links: []
tags: [porting, legacy, 1.7.10, forge-to-fabric, gametest, worldgen, dimension, mixin, biome]
---
# Porting Thaumcraft 4 (1.7.10 Forge) to Minecraft 1.20.1 Fabric

> An ongoing port of the Thaumcraft 4.2.3.5 mod (Minecraft 1.7.10, Forge) to Minecraft 1.20.1 on Fabric, keeping the
> original's behaviour, values and quirks. Each subsystem is checked by GameTests on a real dedicated test server
> (179 passing at the time of writing). Rendering and the custom dimension are checked by screenshots from a real
> client driven by a scripted harness. This note collects the 1.20.1 engine facts that cost time.

## Setup
- Minecraft Java 1.20.1, Fabric Loader 0.16.14, Fabric API 0.92.12+1.20.1, Yarn 1.20.1+build.10 (v2), Loom 1.6.12,
  Gradle 8.8 wrapper, Java 17, on Linux (a cloud container).
- The 1.7.10 reference was decompiled with Vineflower. CFR was the fallback for the classes Vineflower could not
  decompile cleanly.
- Builds ran offline from a populated Loom cache: `./gradlew build runGametest --no-daemon`.

## Route and why
- Route: Fabric loader API plus Mixin, with the original's behaviour re-implemented class by class against its
  decompiled source.
- Forge 1.20.1 was not chosen; the project targets Fabric.
- Data-driven features (dimension, biomes, worldgen, loot, tags) are datapack JSON; everything else is code.

## How the game works (what we had to learn)
- **GameTest server:** it builds its world from the flat preset with only the vanilla dimensions, so datapack
  dimensions do not exist in `runGametest`. The NeoForge 1.21.1 note in this KB reports the same. Test dimension
  content in the overworld, and the dimension itself in a client run.
- **Flat generator:** `FlatChunkGeneratorConfig` treats an all-air layer list as "no terrain" and then runs no biome
  features at all. A void dimension whose biome feature builds everything needs one non-air, non-colliding layer:
  `minecraft:structure_void`, height 1.
- **Experimental-settings warning:** a datapack dimension marks the world experimental in two places.
  `DimensionOptionsRegistryHolder.toConfig` compares the dimension count with vanilla's, and
  `getLifecycle(RegistryKey, DimensionOptions)` flags non-vanilla entries. One mixin patches both: a `@Redirect` on
  `List.size()` in `toConfig` that leaves out the mod's namespace, and a HEAD inject returning `Lifecycle.stable()`.
- **Biome reads are blended:** `World.getBiome(BlockPos)` (`BiomeAccess.getBiome`) picks a neighbouring 4x4x4 cell
  by a seeded offset (`SeedMixer.mixSeed`). A block near a cell edge can therefore report another cell's biome than
  the one just written with `Chunk.populateBiomes`. Gameplay checks that must agree with a biome you set should read
  `world.getBiomeForNoiseGen(BiomeCoords.fromBlock(x), BiomeCoords.fromBlock(y), BiomeCoords.fromBlock(z))`.
- **Enchanting power per block:** 1.20.1 has `#minecraft:enchantment_power_provider` (one point each). The count is
  int local 4 of the lambda `EnchantmentScreenHandler.method_17411(ItemStack, World, BlockPos)`, and `Random.setSeed`
  is called once after counting. A `@ModifyVariable(ordinal = 0)` at that `setSeed` INVOKE adds extra points (e.g. a
  block worth 2 shelves, Forge's old `getEnchantPowerBonus`). Do not hook `calculateRequiredExperienceLevel`: it runs
  three times inside a loop.
- **Forge `onItemUseFirst`:** in 1.7.10 it ran before the block's `onBlockActivated`; in 1.20.1 the block's `onUse`
  runs first and `Item.useOnBlock` only after the block passes. Fabric's `UseBlockCallback` fires before the block on
  both sides, so items that must win over the block (keys, wands) go there.
- **Cancelling an entity spawn:** Forge's `EntityJoinWorldEvent` cancel maps to `ServerEntityEvents.ENTITY_LOAD`.
  Check `age == 0` so chunk reloads are skipped, and queue the discard with
  `server.send(new ServerTask(server.getTicks(), entity::discard))`. `MinecraftServer.execute` runs the task
  immediately when already on the server thread, so it defers nothing.
- **Note-block hook:** every note actually played goes through `NoteBlock.playNote` → `World.addSyncedBlockEvent`;
  inject at that INVOKE. Setting a note block's state directly does not recompute its `instrument`.

## Build steps
1. `./gradlew build runGametest --no-daemon` runs every GameTest on a headless server and writes
   `build/gametest/junit.xml`; parse it rather than trusting the console.
2. A client harness (a gametest-source-set client entrypoint with `--quickPlaySingleplayer` on a prebuilt world)
   places blocks, teleports the player and saves screenshots for visual checks.
3. Look up Yarn names on the mapped jar in the Loom cache with
   `javap -cp .../minecraftMaven/.../minecraft-merged/1.20.1-net.fabricmc.yarn...-v2/*.jar <class>`, and use
   `javap -c -p` to find lambda names and local indexes for mixins.

## Verification
- **Oracles:**
  - GameTests on the real server (`runGametest`), for logic.
  - Screenshots from a real 1.20.1 client, for rendering and for the dimension: portal → `outer_lands`, arrival
    point, generated maze.
- **Each fact above was verified by:**
  - a failing then passing GameTest: biome reads, enchanting power, entity cancel, note hook;
  - a client run: flat layer, experimental warning, dimension;
  - bytecode reading with `javap`: `BiomeAccess`, the `EnchantmentScreenHandler` lambda.
- **Not verified:**
  - the real-client click path through `UseBlockCallback` (tests call the item logic directly);
  - whether patching only one of the two experimental-warning checks would be enough (both were patched together).

## Gotchas
1. **A dimension GameTest finds no world.**
   - **Cause:** the GameTest server has no datapack dimensions.
   - **Fix:** test the content in the overworld and the dimension in a client run.
2. **Void dimension, but the biome feature never runs.**
   - **Cause:** all-air flat layers count as "no terrain", so features are skipped.
   - **Fix:** one `structure_void` layer.
3. **Every world load stops on the experimental-settings screen.**
   - **Cause:** the dimension-count and lifecycle checks.
   - **Fix:** the two-part mixin above.
4. **Crash "asking a region for a chunk out of bound", or log "setBlock in a far chunk", from a feature.**
   - **Cause:** 1.7.10 world generation wrote into neighbouring chunks; 1.20.1 features only see their chunk and a
     small margin.
   - **Fix:** split the generator so each chunk writes only inside itself.
5. **A biome was just written but the check says it wasn't, at some positions only.**
   - **Cause:** `getBiome` blends neighbouring cells by seed.
   - **Fix:** read the stored cell with `getBiomeForNoiseGen`.
6. **Unrelated tests start failing after new tests are added.**
   - **Cause:** test positions moved, and global state keyed by position (static maps, `PersistentState`) leaked
     between tests and batches.
   - **Fix:** each test clears the global state round its own position.
7. **A mob on a pressure plate never presses it.**
   - **Cause:** the mob had `setAiDisabled(true)`. It then never ran its block-collision callbacks. Observed; the exact
     code path was not traced.
   - **Fix:** use an item entity, or a mob with AI.
8. **Mobs in other tests act strangely.**
   - **Cause:** `createMockCreativeServerPlayerInWorld()` adds a real player that every test in the batch sees. It
     also has about 60 ticks of join invulnerability.
   - **Fix:** give such tests their own `batchId`, wait past the invulnerability, and remove the player with
     `world.removePlayer(player, DISCARDED)`.
9. **`ctx.createMockPlayer()` does not compile on 1.20.1.**
   - **Cause:** 1.20.1 only has `createMockSurvivalPlayer`, `createMockCreativePlayer` and
     `createMockCreativeServerPlayerInWorld`.
   - **Fix:** use one of those.
10. **Boss entities spawned in corner chunks are not found by `getEntitiesByClass`.**
    - **Cause:** those chunks were not entity-ticking.
    - **Fix:** `setChunkForced` them during the test.
11. **Crop "growth" code does nothing.**
    - **Cause:** 1.20 crops grow in `randomTick`; 1.7.10 used `updateTick`.
    - **Fix:** call `randomTick`.
12. **A note-block test hears nothing.**
    - **Cause:** the instrument property is not recomputed when the state is set directly, and a per-tick "heard
      notes" list was never cleared.
    - **Fix:** set `instrument` in the test, and clear the list at `END_WORLD_TICK`.

13. **A 1.7.10 multiblock's faces changed with their neighbours (connected textures), and 1.20 models cannot read
    neighbours.**
    - **Cause:** 1.7.10 picked icons at render time (`getIcon(IBlockAccess, x, y, z, side)`).
    - **Fix:** inside a formed structure those inputs never change. Compute them once when it forms, store them as
      block-state properties, and generate one model per state with a script that ports `getIcon`. For TC4's
      infernal furnace that was 231 states, and it keeps vanilla culling and AO.
14. **A screenshot harness reported success, but the picture was from an older run.**
    - **Cause:** the harness's own compile failed, and the wrapper script still exited 0 while the previous PNG
      stayed on disk.
    - **Fix:** fail when no new file is written, and compare the PNG's hash with the last run before looking at it.

## Assets
The original mod's own textures and sounds are used; no generated assets.

## Cost and time
Many sessions. One full `build runGametest` takes about 1–2 minutes from a warm Loom cache.

## Open questions
- Is one of the two experimental-warning checks enough on its own?
- Does the block-collision skip for AI-disabled mobs come from `Entity.move` not running? Not traced in source.
