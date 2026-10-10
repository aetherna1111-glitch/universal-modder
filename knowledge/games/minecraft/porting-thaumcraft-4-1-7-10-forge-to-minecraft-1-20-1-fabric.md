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
- **Gotchas 19-21** were verified the same day with GameTests on the real jars (and a client start).
- **Gotchas 15-18 were verified on 2026-10-10** on a seed-12345 normal world (Windows 10, client window, no Xvfb):
  the log line counts before and after each fix, inspected screenshots, and a GameTest for gotcha 15 (313 passing).
- **Verified later (2026-10-10):** the real-client click path. A screenshot-harness scenario drives
  `ClientPlayerInteractionManager.interactBlock` / `interactItem` in survival (packets, then the server's use path)
  for the wand triggers, the research table, three screens and the infusion altar (refused without its research,
  formed with it): 9 of 9 checks passed. GameTests alone call the item logic directly and cannot show this.
- **Not verified:**
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
15. **Log "Tried to load a block entity for block X but failed" right after world generation.**
    - **Cause:** a 1.7.10 block class covered several metas, only some with a tile entity (`hasTileEntity(meta)`). Ported as
      one `BlockWithEntity` per meta, every state counts as having a block entity. `ChunkRegion.setBlockState` then queues
      a dummy block entity for each placed block, and `createBlockEntity` returns null for the metas without one.
    - **Fix:** only the metas `hasTileEntity` lists implement `BlockEntityProvider` (a subclass); the rest extend `Block`.
      Seen with TC4's eldritch obelisk body; the warning went from 4 per new world to 0.
16. **"Detected setBlock in a far chunk ... currently generating: <your decorator feature>".**
    - **Cause:** a 1.7.10 `BiomeGenBase.decorate(world, rand, x, z)` port keeps populate's frame: it decorates
      x + 8 .. x + 23, the corner of the next chunks. A 1.20 feature may write only 16 blocks around its own chunk, so
      wide trees near the +x / +z edge are refused (clipped) and logged.
    - **Fix:** shift the frame by -8 (`x = chunkX * 16 - 8`): the same 16 x 16 square lands on the decorated chunk and
      trees get 16 blocks of margin on every side. Densities stay the same; positions move 8 blocks for a given seed.
17. **A scripted screenshot client lands underwater or on the ground after a long teleport, though it was flying.**
    - **Cause:** a creative player teleported into chunks the client has not loaded yet counts as on the ground, and the
      client switches creative flight off; it falls once the chunks arrive.
    - **Fix:** put the harness player in spectator mode before long teleports (flight is locked, no hand in the shot).
18. **Finding a naturally generated jigsaw piece (a modded village house) in a test world.**
    - **How:** walk the structure set's `RandomSpreadStructurePlacement.getStartChunk(structureSeed, rx * spacing,
      rz * spacing)` cells, load those chunks only to `ChunkStatus.STRUCTURE_STARTS`, and scan
      `getStructureStarts()` children for `PoolStructurePiece`s. 81 cells took about 12 s.
    - **Gotcha:** match the piece by its template id (`getPoolElement().toString()` contains it). Element identity
      maps break for starts read back from a saved chunk, where the elements are new instances.
19. **"Mod was built with a newer version of Loom (1.7.413), you are using Loom (1.6.12)" when adding a dependency.**
    - **Cause:** Loom refuses to remap a mod jar whose manifest `Fabric-Loom-Version` is newer than the build's Loom.
    - **Fix:** raise Loom to at least that minor (1.6.12 -> 1.7.4 worked on Gradle 8.8 with no other change; 314 tests
      unchanged). Patching the jar's manifest also works but leaves a modified third-party jar to maintain.
20. **`NoClassDefFoundError: net/fabricmc/mapping/tree/TinyMappingFactory` at launch after adding cloth-config.**
    - **Cause:** cloth-config 11.1.118's pom depends on fabric-loader 0.14.21 and an old Fabric API; Gradle pulled them
      onto the dev runtime next to the project's loader.
    - **Fix:** declare such mod dependencies with `{ transitive = false }`.
21. **Trinkets and the Accessories "Trinkets compat layer" in one pack (1.20.1).**
    - **Fact:** accessories-tc-layer 3.8.1+beta.14 declares `"provides": ["trinkets"]` and ships the Trinkets API
      classes. With Trinkets 3.7.2 also installed, Fabric Loader 0.16.14 keeps the layer (higher version) as
      `trinkets` and silently drops real Trinkets: it is not in the loaded-mod list.
    - **Consequence:** an Accessories-based mod needs no Trinkets adapter; Trinkets-API mods see its items through the
      layer (`TrinketsApi.getTrinketComponent(player).isEquipped(item)` was true for items worn in Accessories slots).
      With real Trinkets and no layer, the two inventories are separate and that call returns false.
22. **"No data fixer registered for <modid>:<entity>" ERROR lines at every start, also on a production server.**
    - **Cause:** `EntityType.Builder.build(id)` calls `Util.getChoiceType` only to validate the id against vanilla's
      data fixer schema and drops the result; mod ids are never in it.
    - **Fix:** a `@Redirect` of that call in `EntityType.Builder.build` returning null for your namespace (or build the
      types without the vanilla builder). Saving and loading do not depend on it; all 318 tests still passed.
23. **Testing the release jar, not the dev runtime.**
    - **How:** the official server launcher `https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/<installer>/server/jar`,
      the remapped jar plus real dependency jars in `mods/`, `eula=true`, pipe `stop` once the log says `Done (`, then
      fail on `/ERROR]` lines or stack-trace lines. Do not fail on every line mentioning "Exception": Accessories logs a
      WARN with `ClassNotFoundException` for its client-only mixin target on servers.
24. **Code and assets still following the version the port was first based on.**
    - **Symptom:** the project had been switched from a Thaumcraft 5 base to Thaumcraft 4, and every audit
      compared gameplay with TC4, yet the wand was still drawn with TC5's OBJ mesh and TC5 textures, and
      several projectile and hole renderers, an input handler and a swing hook still did what TC5 did.
    - **Fix:** grep comments and identifiers for the old version's name ("TC5", "1.8.9") and audit each hit
      against the real reference; hash every texture and model and compare with the reference jar's assets
      (here 16 textures and a whole folder of OBJ meshes had no TC4 origin; 9 icons that looked new were exact
      crops of a TC4 sprite sheet). Removing an unused leftover is safe only after grepping for its path.
25. **A regression test that could not fail.**
    - **Symptom:** a test for "one toggle per key press" held the key for 40 ticks with key repeat; the toggle
      cycles mod 3 and 40 is 1 mod 3, so the old buggy code passed too. A cooldown test used a focus whose
      cooldown is 0 ms and failed for a reason unrelated to the change.
    - **Fix:** choose inputs so the old behaviour gives a different answer (30 repeats: old 0, new 1), and read the
      reference values the test depends on before writing it.
26. **Systematic renderer drift that per-feature tests never catch.**
    - **Symptom:** every gameplay test passed, yet the thaumometer was invisible, infused ores were solid tinted
      cubes, glows were cut into hard blobs and many item icons faced the wrong way.
    - **Fix:** a read-only audit per renderer group (tile, block / ISBRH, entity, item), each comparing the port's
      code with the original renderer line by line (GL state, matrix sequence, brightness, UVs) and returning
      findings ranked by visibility; then one harness scenario that places every flagged object and is re-run after
      each fix. About 60 differences surfaced in one pass; none was TC5-derived. Details of the GL11 to 1.20 mapping:
      `techniques/porting-1-7-10-gl11-render-states-and-iitemrenderer-frames-t.md` (its own branch).
27. **Item icons of TESR-drawn blocks a quarter-turn off.**
    - **Symptom:** chests showed their back, tables and the bore base faced away; adding TC4's own
      renderInventoryBlock rotations did not help.
    - **Cause:** the shared `builtin/entity` item model used vanilla 1.20's GUI rotation (30, 225, 0); 1.7.10's
      INVENTORY_BLOCK frame works out to (30, -45, 0) (RenderItem's z flip, 210 X, 45 Y, -90 Y).
    - **Fix:** give the shared builtin model the 1.7.10 frame; then each item replays the original's own turns
      exactly.
28. **A visual check that "found nothing" because of the camera, not the code.**
    - **Symptom:** a 1-in-20 random overlay never appeared in screenshots; forcing it on every face showed it.
    - **Cause:** the creative player fell after the teleport, so the "overview" was a close-up containing none of
      the predicted positions. The positions were confirmed with jshell (java.util.Random), then in the game log.
    - **Fix:** for random decorations, compute the expected positions with the original RNG first and aim the
      camera at them (and remember a teleported creative player does not keep flying).

## Assets
The original mod's own textures and sounds are used; no generated assets.

## Cost and time
Many sessions. One full `build runGametest` takes about 1–2 minutes from a warm Loom cache.

## Open questions
- Is one of the two experimental-warning checks enough on its own?
- Does the block-collision skip for AI-disabled mobs come from `Entity.move` not running? Not traced in source.
