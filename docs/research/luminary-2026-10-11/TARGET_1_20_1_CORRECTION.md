# Target correction: Luminary targets Minecraft Java **1.20.1**

Owner instruction received 2026-10-11 after the first research pass: Luminary targets **1.20.1**, not 1.21.1.
Earlier files in this directory that reference 1.21.1 are kept unmodified (add-only mandate); this file states
which of their conclusions no longer apply and which still hold. The authoritative 1.20.1 material is in the files
suffixed `_1_20_1`.

## Universal Modder's own configuration is unrelated to Luminary's target (VERIFIED)

`universal-modder` has no Minecraft version of its own: it is a Python toolkit (`pyproject.toml`, `um/`). Its only
Fabric project, `examples/minecraft-gta5-passthrough/mc`, targets Minecraft 26.3 / Java 25 and is a worked example,
not a constraint. `thaumcraft-fabric` targets 1.20.1 / Java 17. Nothing in either repository forces Luminary's version;
the owner's choice of 1.20.1 is consistent with the Thaumcraft port, Create Fabric and the Kindred precedent.

## Conclusions from the 1.21.1 files that are superseded

| Earlier statement (file) | Status now |
|---|---|
| Toolchain triple Yarn `1.21.1+build.3`, Fabric API `0.116.17+1.21.1` (`MINECRAFT_FABRIC_1_21_1.md` §1, §9) | **Superseded** → Yarn `1.20.1+build.10`, Fabric API `0.92.12+1.20.1`, Java 17 (`MINECRAFT_FABRIC_1_20_1.md`) |
| Loom 1.17.x + Gradle ≥ 9.5 recommendation | **Superseded** → Loom 1.10.5 (JVM 17, Gradle ≥ 8.12) or Loom 1.6.12 (the port's line) (`BUILD_CONFIGURATION_1_20_1.md`) |
| `PersistentState.Type(Supplier, BiFunction<Nbt, WrapperLookup, T>, DataFixTypes)`; `writeNbt(nbt, lookup)` | **Superseded** → `getOrCreate(Function<NbtCompound,T>, Supplier<T>, String)`; `writeNbt(NbtCompound)` |
| `CustomPayload` + `PayloadTypeRegistry` networking | **Superseded** → `FabricPacket`/`PacketType` or `Identifier` channels with `PacketByteBuf` |
| `FabricEntityTypeBuilder` deprecated; use `EntityType.Builder` + `FabricEntityType` | **Superseded** → `FabricEntityTypeBuilder.create(SpawnGroup, factory)` is the current, non-deprecated API on 1.20.1 |
| `Identifier.of(ns, path)` | **Superseded** → `new Identifier(ns, path)` |
| Data folders singular (`recipe/`, `loot_table/`, `tags/block/`) | **Superseded** → plural on 1.20.1 (`recipes/`, `loot_tables/`, `tags/blocks/`, `tags/items/`) — LIKELY (standard 1.20 layout; the `thaumcraft-fabric` resources use `data/`; not re-read in this session) |
| GeckoLib 4.9.3 | **Superseded** → GeckoLib **4.8.4** for Fabric 1.20.1 (2026-06-20) |
| "Create Fabric has no 1.21.1 build, so the owner's stack cannot load with Luminary" (`DEPENDENCIES_AND_COMPATIBILITY.md` §1) | **Resolved**: on 1.20.1 Create Fabric 6.0.8.1 and the Thaumcraft port load together with Luminary |
| Resolution table R1/R2/R3 in `THAUMATURGY_RESEARCH.md` §3 | **Resolved**: R1 chosen by the owner; R2/R3 moot |
| Modonomicon/Patchouli/Polymer/permissions API availability "for 1.21.1" | **Re-verified for 1.20.1** (all present, versions in `DEPENDENCIES_AND_COMPATIBILITY_1_20_1.md`) |
| Open question Q1 (version) in `OPEN_QUESTIONS.md` | **Closed** |
| `GameTest` annotation fields incl. `skyAccess`, `manualOnly` | **Partly superseded**: 1.20.1 has `templateName, batchId, tickLimit, required, maxAttempts, requiredSuccesses, rotation` only |

## Conclusions that still hold unchanged

- Everything version-independent in `LUMINARY_SYSTEM_DESIGN.md` (module boundaries, pure-Java core, Catalog/
  Progression/Story models, persistence-as-JSON-in-NBT, idempotent effects), `AI_NPC_TECHNICAL_RESEARCH.md`
  (boundary, typed actions, memory model, provider abstraction, Ollama API facts), `SECURITY_AND_PERFORMANCE.md`,
  `TESTING_AND_VALIDATION.md`, `PROJECT_MAP.md`, `EXISTING_ARCHITECTURE.md`, `SESSION_NOTES.md`.
- Fabric Loader 0.19.5 is the newest loader for 1.20.1 as well (VERIFIED, `meta.fabricmc.net`).
- Lifecycle, tick, connection and command event names are identical on the `1.20.1` branch (VERIFIED, see
  `MINECRAFT_FABRIC_1_20_1.md`).
- The stray untracked `luminary/` scaffold (`SESSION_NOTES.md`) pins 1.21.1 values and is now doubly obsolete;
  recommendation unchanged: delete it.
