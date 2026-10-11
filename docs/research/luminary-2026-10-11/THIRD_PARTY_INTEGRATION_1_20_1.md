# Third-party integration on 1.20.1: what changes versus the 1.21.1 notes

`THIRD_PARTY_MOD_APIS.md` §1 (integration layer: `ModIntegration`, `IntegrationRegistry`, entrypoint-based plugins,
class-loading isolation) and §9 are version-independent and still apply. Version-specific deltas:

## Fabric Loader facilities (VERIFIED API names, loader 0.19.5 — same on both versions)

`FabricLoader.getInstance().isModLoaded(String)`, `getModContainer(String)` → `ModContainer.getMetadata().getVersion()`,
`getEntrypointContainers(String key, Class<T>)`; custom entrypoint keys declared in `fabric.mod.json`
`"entrypoints": {"luminary": ["..."]}`. The 1.20.1 port already registers `main` and `client` entrypoints this way.

## Thaumcraft 4 port — now in-process (1.20.1)

Since both mods run on the same server, the bridge options are:

| Option | Changes to the port | Robustness | Recommendation |
|---|---|---|---|
| A. Port implements a `LuminaryPlugin` entrypoint and calls a tiny `dev.luminary.api` | yes (one class + entrypoint line; needs the other agent / owner) | high: compile-time contract, no mixins | **preferred** |
| B. Port exposes its own `thaumcraft.api.event` (research completed, scan performed, aspect discovered) and Luminary subscribes | yes (an event package) | high; also useful to `createtaumechanics` | good; A and B can coexist |
| C. Luminary mixins into `thaumcraft.research.ResearchManager`/`ScanManager` | none | low: breaks on the port's refactors; the port is mid-port | only as a stopgap |
| D. Luminary polls `PlayerKnowledge` through reflection | none | lowest | avoid |

Data-level gating stays as in `THAUMATURGY_RESEARCH.md` §2: a Luminary-granted TC4 research entry as prerequisite of
TC4 categories, if `ResearchLoader`'s JSON allows external prerequisite ids (UNVERIFIED).

The port's `CLAUDE.md` constrains **gameplay fidelity**, not interoperability; adding an outbound event API does not
change TC4 behaviour. Still, it is a change to a repository another agent owns: propose, do not apply.

## Create Fabric 6.0.8.1 (1.20.1)

Public-ish surfaces (LIKELY, read the 6.0.8.1 sources before use): recipe types `create:mixing`, `create:pressing`,
`create:item_application`, `create:sequenced_assembly`; block tag `create:casing`; `KineticBlockEntity` speed/stress;
ponder. `createtaumechanics` already compiles against this version (commit message), so its `build.gradle` is the
local reference for the dependency declaration (not read in this session).

## GeckoLib 4.8.4 (1.20.1)

Same model as in `AI_NPC_TECHNICAL_RESEARCH.md` §7: server syncs a `TaskPhase` via `DataTracker`; client
`AnimationController` maps it to clips. Verify in the 4.8 wiki: `GeoEntity`, `AnimatableInstanceCache`,
`AnimationController`, `triggerAnim` (UNVERIFIED for 4.8).

## FTB Quests 2001.4.22 (1.20.1 Fabric, `maven.ftb.dev`)

Treat as an **input**: quest completion → `Prerequisite.Signal("ftbquests:<quest-id>", 1)`. Needs their API events;
UNVERIFIED. Kindred read quest titles only as hints (VERIFIED as reported).

## Modonomicon 1.79.3 (1.20.1 Fabric)

Loader-neutral bridge: Luminary grants a hidden advancement `luminary:discovery/<id>` when a discovery becomes
VERIFIED; the book entry's unlock condition references that advancement (LIKELY supported; Modonomicon documents
advancement conditions). No code dependency on Modonomicon needed. Per-player visibility comes for free; "public
knowledge" = grant the advancement to all online players and on join (idempotent).
