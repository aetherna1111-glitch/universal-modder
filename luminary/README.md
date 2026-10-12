# Luminary (Fabric mod, Minecraft 1.20.1)

Server-authoritative foundation for **Luminary**: civilization progression, the shared **Catalog of the Unknown**,
thaumaturgy integration, a controlled narrative and an autonomous NPC. This directory is the **first implementation
slice**; design and research live in `../docs/research/luminary-2026-10-11/` (start with `README.md`,
`LUMINARY_SYSTEM_DESIGN.md` and `LUMINARY_SYSTEM_DESIGN_1_20_1.md`).

## Status

| Area | State |
|---|---|
| Pure-Java core: ids, event bus, Catalog rules (observe / evidence / verify / applied), JSON codec, schema migrations (v1→v2) | **implemented, unit-tested** |
| Pure-Java core: Progression — eras, technologies, prerequisites (era / verified discovery / unlocked technology / signal), automatic cascading unlocks, era advancement, admin override, capabilities | **implemented, unit-tested** |
| Pure-Java core: Story — ordered phases (admin-controlled; opt-in auto-advance on explicit conditions), strategic branches, authored clues with reveal conditions and no override, full phase history; schema v3 | **implemented, unit-tested** |
| Pure-Java core: NPC — definitions, data-driven dialogue scripts with story conditions and clue lines, `DialogueProvider` abstraction with `scripted` (default) and `disabled` providers, roster (one body per NPC, per-player memory), own persisted state `luminary_npc.dat` | **implemented, unit-tested** (43 tests in total) |
| Fabric layer: mod init, `PersistentState` (`<world>/data/luminary.dat`), data-pack loader (discoveries, eras, technologies, story phases/clues/branches, npcs, dialogues), `/luminary catalog`, `/luminary progression`, `/luminary story`, `/luminary npc` commands, en/ru lang | **implemented**; covered by 9 GameTests |
| Fabric layer: NPC entity (`luminary:npc`, persistent, never despawns, right-click to talk), roster reconciliation on entity load/unload (duplicates discarded, missing bodies forgotten), follow goal, `config/luminary.json` (`dialogueProvider`), placeholder client renderer (default player model) | **implemented**; entity + dialogue covered by GameTests; **client rendering not verified** (no client in CI) |
| Gameplay enforcement of capabilities (recipe/item gates), City (facilities), NPC tasks (gathering, furnaces), model-backed dialogue provider, GeckoLib model, mod integrations | **designed, not implemented** — see the research set |
| Thaumcraft 4 port bridge (`dev.luminary.api` plugin entrypoint) | **proposed**, needs the port maintainers |

Nothing here grants items, unlocks recipes or changes gameplay yet. The slice proves the architecture: a Minecraft-free
core, server-side rules, persistence with migrations, admin commands, and an in-world test harness.

## Last recorded verification (2026-10-12, slice 4, JDK 21 runtime, `release 17`)

| Check | Result |
|---|---|
| `./gradlew build` (compile main/test/gametest, 43 unit tests, jar) | passed; `build/libs/luminary-0.1.0.jar` (190 KB) |
| `./gradlew runGametest` (headless 1.20.1 server, Fabric API 0.92.12, Loader 0.19.5) | "All 9 required tests passed" |
| `um publish check luminary` | 0 failures (1 warning in the ignored `.gradle/` cache) |
| Not verified | a real dedicated server with players; the integrated client and the NPC renderer; any other mod loaded alongside |

## Requirements

- JDK **17** (JDK 21 also builds and runs the dev server; the jar targets release 17)
- Gradle wrapper **8.8** (bundled), Fabric Loom **1.6.12**
- Minecraft **1.20.1**, Fabric Loader ≥ 0.16 (0.19.5 used), Fabric API **0.92.12+1.20.1**

## Build, test, run

```bash
cd luminary
./gradlew build            # compiles main + gametest, runs the 18 unit tests, writes build/libs/luminary-<ver>.jar
./gradlew test             # unit tests only (pure core + architecture rule)
./gradlew runGametest      # headless GameTest server: build/gametest/, JUnit XML in build/gametest/junit.xml
./gradlew runServer        # dev dedicated server in run/server/ (accept the EULA in run/server/eula.txt first)
```

## Commands (server side, op level in brackets)

```
/luminary status                                [2]  templates / records / executed effects
/luminary catalog templates                     [2]  what the data packs define
/luminary catalog list                          [2]  records with status and attribution
/luminary catalog observe <id> [player]         [2]  first observer is attributed forever
/luminary catalog sample <id> <item> <count>    [2]  hand in sample evidence
/luminary catalog verify <id>                   [3]  rules enforced (contributors, samples, experiments, dependencies)
/luminary catalog verify <id> force             [4]  administrative override, recorded in the record's history
/luminary progression status                    [2]  era, unlocked technologies, capabilities, signals
/luminary progression eras                      [2]  eras with prerequisites; current era marked
/luminary progression technologies              [2]  each technology with its still-missing prerequisites
/luminary progression unlock <id> [force]       [3/4] explicit unlock; force bypasses prerequisites and is recorded
/luminary progression signal <id> <value>       [4]  set an external signal (integrations will do this in code)
/luminary story status | phases | clues         [2]  phase, branch, revealed / revealable clues
/luminary story set <phase>                     [4]  administrative phase change (forward or back), recorded with actor
/luminary story branch <branch>                 [4]  commit to a strategic approach
/luminary story reveal <clue>                   [3]  surface an authored clue; refused while locked — there is no override
/luminary npc list                              [2]  roster: body present / missing / never spawned, last position, players remembered
/luminary npc spawn <npc>                       [3]  one body per NPC, at the executor's position; refused while a body lives
/luminary npc despawn <npc>                     [3]  removes the body; memory and record stay
/luminary npc follow <npc> <player> | stop      [3]  follow order (walks to within ~2.5 blocks, repaths once per second)
/luminary npc talk <npc>                        [2]  talk without clicking (player executor, body must be present)
/luminary npc provider <scripted|disabled>      [4]  swap the dialogue provider until restart
```
Players talk by right-clicking the NPC. Lines arrive in chat as `<NPC name> line`.
Ids are Minecraft identifiers: always write the namespace (`luminary:material/first_trace_ore`).

## Data packs: discovery templates

`data/<namespace>/luminary/discoveries/<name>.json`

```json
{ "id": "luminary:phenomenon/deviation_zone",
  "title": "discovery.luminary.phenomenon.deviation_zone",
  "verification": { "minContributors": 3, "minSamples": 0, "experiments": ["luminary:experiment/compass_drift"], "requiresVerified": [] },
  "dependsOn": ["luminary:material/first_trace_ore"],
  "visibility": "public_when_verified" }
```
Files with errors are logged with their path and skipped; a dependency on an unknown template rejects the whole
reload and keeps the previous set. Three example templates ship in `src/main/resources/data/luminary/...`.

## Data packs: eras and technologies

`data/<ns>/luminary/eras/<name>.json` and `data/<ns>/luminary/technologies/<name>.json`

```json
{ "id": "luminary:era/town", "order": 2, "title": "era.luminary.town",
  "prerequisites": [ { "type": "technology_unlocked", "technology": "luminary:tech/ore_assay" },
                     { "type": "signal", "signal": "luminary:signal/luminarium_founded", "min": 1 } ] }

{ "id": "luminary:tech/ore_assay", "era": "luminary:era/settlement", "title": "technology.luminary.tech.ore_assay",
  "prerequisites": [ { "type": "era_at_least", "era": "luminary:era/settlement" },
                     { "type": "discovery_verified", "discovery": "luminary:material/first_trace_ore" } ],
  "applies": [ "luminary:material/first_trace_ore" ],
  "grants": [ "luminary:cap/assay_bench" ] }
```
Prerequisite types: `era_at_least`, `discovery_verified`, `technology_unlocked`, `signal` (`min` defaults to 1).
Rules: the first era (lowest `order`) has no prerequisites; orders are unique; all references must resolve; the
technology-requires-technology graph must be acyclic. Violations reject the whole reload and keep the previous set.

Semantics: technologies unlock **automatically** as soon as their prerequisites hold (civilization-level knowledge,
not a per-player purchase); unlocking marks `applies` discoveries APPLIED in the Catalog; the next era is entered when
its prerequisites hold; eras never go backwards. Evaluation re-runs after every Catalog status change, unlock, era
change and signal change (see ADR-0003).

## Data packs: story

`data/<ns>/luminary/story/phases/*.json`, `.../story/clues/*.json`, `.../story/branches/*.json`

```json
{ "id": "luminary:story/first_trace", "order": 1, "title": "story.phase.luminary.first_trace", "autoAdvance": true,
  "conditions": [ { "type": "discovery_verified", "discovery": "luminary:material/first_trace_ore" } ] }

{ "id": "luminary:clue/compass_lie", "phase": "luminary:story/deviation", "text": "story.clue.luminary.compass_lie",
  "conditions": [ { "type": "clue_revealed", "clue": "luminary:clue/ore_hum" } ] }

{ "id": "luminary:story/branch/containment", "title": "story.branch.luminary.containment" }
```
Condition types: every progression prerequisite type plus `branch_chosen`, `clue_revealed`, `phase_at_least`.
Semantics (ADR-0004): phases change only by an administrator, except phases that set `autoAdvance: true`, which are
entered automatically when their conditions hold; admin changes may go backwards and are always recorded. A clue can be
revealed only once its phase is reached and its conditions hold; there is deliberately no override. Clue texts are
authored lang entries: the future NPC/LLM layer may only ask `mayReveal` and quote the authored text.
The bundled phases are the seven working titles from the vision (0 Zero Meridian … VI Beyond the Boundary).

## Data packs: NPCs and dialogue

`data/<ns>/luminary/npcs/*.json` and `data/<ns>/luminary/dialogues/*.json`

```json
{ "id": "luminary:npc/ascending_intelligence", "name": "npc.luminary.ascending_intelligence",
  "dialogue": "luminary:dialogue/ascending_intelligence" }

{ "id": "luminary:dialogue/ascending_intelligence", "lines": [
  { "text": "dialogue.luminary.ai.greeting_1", "priority": 0 },
  { "text": "dialogue.luminary.ai.ore_verified", "priority": 1,
    "conditions": [ { "type": "discovery_verified", "discovery": "luminary:material/first_trace_ore" } ] },
  { "text": "dialogue.luminary.ai.branch_investigation", "priority": 1,
    "conditions": [ { "type": "branch_chosen", "branch": "luminary:story/branch/investigation" } ] },
  { "text": "story.clue.luminary.ore_hum", "priority": 5, "clue": "luminary:clue/ore_hum" } ] }
```
Line `conditions` use the story condition types. Selection (`scripted` provider): a line is eligible when its
conditions hold; a line with `clue` is eligible only while `StoryController.mayReveal(clue)` is true **and** the clue
is not revealed yet (it is news, spoken once per civilization). Among eligible lines the highest `priority` wins; ties
rotate per player (the roster remembers the last line each player heard). Speaking a clue line goes through
`StoryController.reveal`, so story conditions can never be bypassed by dialogue — a provider that names a clue the
story refuses gets the fallback line instead (ADR-0005). Every meeting is remembered (count, first/last time, name).

`config/luminary.json`: `{ "dialogueProvider": "scripted" }` — or `"disabled"` (NPCs answer with the fallback line
"…" and gameplay continues). No external service, key or network call exists in this slice.

Persistence: the body is a normal entity saved with its chunk (`LuminaryNpc` id, follow target); identity, last
position and memory live in `<world>/data/luminary_npc.dat` (own schema, version 1). On entity load the roster
reconciles: a second body for the same NPC is discarded, a body whose registered predecessor no longer exists takes
over, a body whose definition was removed from the data packs stays but only says the fallback line (logged).

## Layout

```
src/main/java/dev/luminary/core/        pure Java (no net.minecraft / net.fabricmc imports; enforced by a test)
  ids/ events/ catalog/ progression/ story/ npc/ persistence/
src/main/java/dev/luminary/fabric/      Minecraft-facing: Luminary (init), LuminaryRuntime, state/, command/, definitions/, npc/, config/, client/
src/test/java/                          JUnit 5
src/gametest/java/                      Fabric GameTests (second Loom mod "luminary-gametest", never shipped)
src/gametest/resources/data/            test-only data pack (test NPCs, a clue gated by a test-only signal)
docs/decisions/                         ADRs
```

## Persistence

The whole `CivilizationState` is JSON (hand-written codec, explicit field names) stored as a string inside vanilla's
persistent-state NBT with a `schemaVersion`. Migrations are ordered JSON-tree steps; a document newer than the running
build is **refused** and the server continues with a non-persisting empty view instead of overwriting it.

## Conventions

English for code, comments, docs and commits. Player-facing text goes through lang files (`en_us`, `ru_ru`).
No secrets in the repository. Never claim a build or test passed without running it.
