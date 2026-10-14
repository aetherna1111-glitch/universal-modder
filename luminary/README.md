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
| Pure-Java core: NPC — definitions, data-driven dialogue scripts with story conditions and clue lines, `DialogueProvider` abstraction with `scripted` (default) and `disabled` providers, roster (one body per NPC, per-player memory), own persisted state `luminary_npc.dat` | **implemented, unit-tested** |
| Pure-Java core: NPC tasks — typed `NpcAction` (`GatherWood`, `MoveTo`, `Deposit`, `Withdraw`, `Smelt`), validator chain, explicit task states, item ledger (broken / collected / deposited / withdrawn / smelted), authorized containers, persisted in-flight task with its requester | **implemented, unit-tested** (100 tests in total) |
| Pure-Java core: language-model layer — `LlmProvider` with `none` (default), `ollama` and `openai` (any OpenAI-compatible endpoint) backends; async off the game thread, connect/request timeouts, 64 KiB response cap, concurrency limit, exponential cooldown, loopback-only endpoints unless allow-listed, API key from the environment only, JSONL journal | **implemented, unit-tested against a mock HTTP server** |
| Pure-Java core: NPC memory — typed entries (stable facts, interactions, events, promises, relationships, observations, clues, assumptions) with provenance and trust, topic supersession, per-player and global caps that never evict promises, budgeted relevance retrieval, deterministic summarization; every Catalog/Progression/Story event becomes an observed memory of every NPC | **implemented, unit-tested** |
| Pure-Java core: model-backed dialogue — `model` provider on top of the LLM layer: persona + retrieved memories + public context + only the clues the story allows right now go into one structured request; the reply `{line, clue?, note?}` is validated (bounded line, clue outside the allowed set dropped, note stored as `MODEL_INFERRED`); any failure or timeout falls back to the `scripted` provider; the dialogue service is asynchronous and finishes every turn on the server thread | **implemented, unit-tested** (incl. end-to-end through the HTTP client against a loopback Ollama look-alike) |
| Pure-Java core: intent planning — `IntentPlanner` turns a player's words into at most one typed action chosen among the candidates the server saw (trunk bases, authorized containers with contents, the player's position): `keyword` (deterministic rules, English and Russian, default), `model` (structured request, indices and item ids only, grounded or dropped, falls back to keyword), `none`. The action still goes through `NpcService.assign` and every validator; a request becomes a `PROMISE` memory, its outcome a `SIGNIFICANT_EVENT` that supersedes the promise | **implemented, unit-tested** |
| Pure-Java core: autonomy — per-NPC switch plus a server-wide flag (both off by default), persisted standing orders (`StockWood`: keep a chest stocked), reflexes (idle with full hands → deposit), a sliding budget of self-started tasks per hour, failed or refused targets avoided for a cooldown; deterministic, no model; every decision goes through the same validators as a command and is remembered | **implemented, unit-tested** |
| Fabric layer: mod init, `PersistentState` (`<world>/data/luminary.dat`), data-pack loader (discoveries, eras, technologies, story phases/clues/branches, npcs, dialogues), `/luminary catalog`, `/luminary progression`, `/luminary story`, `/luminary npc` commands, en/ru lang | **implemented**; covered by 9 GameTests |
| Fabric layer: NPC entity (`luminary:npc`, persistent, never despawns, right-click to talk), roster reconciliation on entity load/unload (duplicates discarded, missing bodies forgotten), follow goal, `config/luminary.json` (`dialogueProvider`), placeholder client renderer (default player model) | **implemented**; entity + dialogue covered by GameTests; **client rendering not verified** (no client in CI) |
| Fabric layer: task executor — real block breaking (`ServerWorld.breakBlock`, loot tables), item-entity collection into the NPC's own inventory, insertion into the authorized container's `Inventory`, per-step re-validation, resume after reload; `/luminary npc task` and `/luminary npc container` commands | **implemented**; covered by 8 GameTests incl. item accounting |
| Fabric layer: chat intake — the nearest NPC within 8 blocks hears a player's chat message; the planner looks for work first (candidates scanned around the body), an action is assigned like a command and the result spoken back, otherwise the line is a dialogue turn; the requester is told when the task ends; `/luminary npc intent`, `/luminary npc planner`, `/luminary npc task move|deposit|withdraw|smelt`; `/luminary npc memory` commands | **implemented**; covered by 8 GameTests (chat → task → chest → memory with no model; a standing order stocking a chest with no command; a real furnace run with full item accounting) |
| Fabric layer: integrations — `thaumcraft-observer` entrypoint applying `ThaumcraftBridge` translations on the server thread | **implemented**; 1 GameTest drives the entrypoint directly |
| Pure-Java core: living presence — versioned character sheet, gaze reactions and spatial whispers with cooldowns, bounded multi-turn conversation context, karma/trust/threat relationships with forgiveness, self-defence state machine (shield first, 3..5 hits, hold ground, no pursuit), real-time knockout, unique-weapon custody with exactly-one-hour theft and retired instances, daily routine, persistent multi-step plans, capability allowlist, metrics; NPC state schema v2 | **implemented, unit-tested** (144 tests in total) |
| Fabric layer: `BehaviourService`, `CombatGoal`, `RoutineGoal`, `LuminBladeItem` (`luminary:lumin_blade`), knockout pose synced to clients, `/luminary npc behaviour|relations|revive|weapon|place|routine|plan`, `/luminary metrics` | **implemented**; covered by 6 GameTests on an injected clock (knockout, the one-hour return) |
| Gameplay enforcement of capabilities (recipe/item gates), City (facilities), further NPC tasks (milking, crafting, planting) and standing orders, model-proposed orders, GeckoLib model, mod integrations | **designed, not implemented** — see the research set |
| Thaumcraft 4 port bridge — Luminary declares the Fabric entrypoint `thaumcraft-observer` (`Consumer<Map<String,Object>>`, no shared classes); research completions, scans and aspect discoveries become progression signals (`signal/thaumcraft/...`) and NPC observations; the port-side patch is in `docs/integration/` | **both sides implemented and tested** (port branch `claude/dreamy-knuth-rizfww` on top of the port's working branch, 330/330 GameTests there; server start verified with Create and the Taumechanics addon) |

Nothing here grants items, unlocks recipes or changes gameplay yet. The slice proves the architecture: a Minecraft-free
core, server-side rules, persistence with migrations, admin commands, and an in-world test harness.

## Last recorded verification (2026-10-14, slice 16, JDK 21 runtime, `release 17`)

| Check | Result |
|---|---|
| `./gradlew build` (compile main/test/gametest, 162 unit tests, jar) | passed |
| `./gradlew runGametest` (headless 1.20.1 server, Fabric API 0.92.12, Loader 0.19.5) | "All 49 required tests passed" (44 before the trade tests, 46 with them, 47 with the blocked-shield test, 48 with karma, 49 with chat topics) |
| `um publish check luminary` | 0 failures (1 warning in the ignored `.gradle/` cache) |
| Not verified | a real dedicated server with players; the integrated client, the NPC renderer, the blade item model and the lying pose; any other mod loaded alongside; a real Ollama or hosted model endpoint (only the mock server was exercised) |

## Setup

`docs/SETUP.md`: installing the jar, running a local model with Ollama or a hosted API, the first conversation and
task, autonomy, the Thaumcraft port.

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
/luminary npc provider <scripted|disabled|model> [4]  swap the dialogue provider until restart
/luminary npc gear <npc>                        [2]  hand, armour and bag with durability
/luminary npc behaviour <npc>                   [2]  combat state, knockout deadline, weapon custody, routine, plan
/luminary npc relations <npc>                   [2]  karma / trust / threat and standing per player
/luminary npc revive <npc>                      [3]  end a knockout now
/luminary npc weapon <npc> [reclaim|transfer <player> <reason>]  [2/4]  custody; the single permanent-transfer point
/luminary npc trade <npc> status [player]             [3]  points, tasks and purchases of a player
/luminary npc trade <npc> complete [player] [all]     [3]  every task done by decree ("all" includes the trial: the blade changes hands)
/luminary npc trade <npc> reset [player]              [3]  the ledger wiped; a given blade reclaimed
/luminary npc trade <npc> points <player> <amount>    [3]  grant (or take, negative) points
/luminary npc relations <npc> reset [player]          [3]  karma, trust and threat with the NPC back to zero
/luminary npc relations <npc> karma <player> <value>  [3]  karma set outright (-100..100)
/luminary npc place <npc> <study|desk|site|home|workshop> <pos> | clear <kind>  [3]  routine places
/luminary npc routine <npc> on|off              [3]
/luminary npc plan status|cancel <npc>          [2/3]
/luminary npc plan stock <npc> <tree> <chest> <logs> | smelt <npc> <chest> <furnace> <item> <count>  [3]
/luminary metrics                               [2]  counters and latencies
/luminary npc planner <none|keyword|model>       [4]  swap the intent planner until /reload or restart
/luminary npc autonomy server on|off             [4]  server-wide autonomy switch until /reload or restart
/luminary npc autonomy <npc> [on|off]            [3]  per-NPC switch; without on/off: status, budget, orders
/luminary npc order stock_wood <npc> <container pos> <minLogs>  [3]  standing order (container must be authorized)
/luminary npc order clear <npc>                 [3]  drop all standing orders
/luminary npc intent <npc> <text>               [2]  say something to an NPC from anywhere (same path as nearby chat)
/luminary npc container allow|deny <npc> <pos>  [3]  authorize (persisted) a container the NPC may deposit into
/luminary npc task gather <npc> <log pos> <container pos> [maxLogs]   [3]  validated gather-wood task
/luminary npc task move <npc> <pos>             [3]  walk to within reach of a position
/luminary npc task deposit <npc> <container pos> [3]  put everything carried into an authorized container
/luminary npc task withdraw <npc> <container pos> <item id> [count]  [3]  take items out of an authorized container
/luminary npc task smelt <npc> <furnace pos> <container pos> <item id> [count]  [3]  smelt items from an authorized container in a furnace
(chat) "use this chest" while looking at it     [conversation.containerGrantPermission]  hand the NPC a container; "forget this chest" takes it back
/luminary npc task status <npc>                 [2]  state, failure reason, ledger (broken / collected / deposited / withdrawn), requester
/luminary npc task cancel <npc>                 [3]  marks the task FAILED(cancelled); carried items stay with the NPC
/luminary npc memory <npc> [player]             [2]  what the NPC remembers (newest 30), optionally about one player
/luminary npc memory note <npc> <text>          [3]  add an authored stable fact (the NPC's teaching knowledge)
/luminary llm status                            [2]  provider, model, endpoint, health, request counters
/luminary llm ping                              [4]  one tiny request to the configured model; the reply or failure arrives asynchronously
```
Players talk by right-clicking the NPC **or by chatting within 8 blocks of it**; lines arrive in chat as `<NPC name> line`.
The chat message itself is never consumed or changed.
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
{ "id": "luminary:npc/lumin", "name": "npc.luminary.lumin",
  "dialogue": "luminary:dialogue/lumin",
  "persona": [ "An old intelligence that remembers the first fire.", "Speaks briefly, asks before telling." ] }

{ "id": "luminary:dialogue/lumin", "lines": [
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

`persona` (optional, strings) is ignored by the `scripted` provider and becomes the character section of the model
prompt for the `model` provider.

`config/luminary.json`: `{ "dialogueProvider": "scripted" }` — or `"disabled"` (NPCs answer with the fallback line
"…" and gameplay continues), or `"model"` (see "Model-backed dialogue" below; without a configured model it behaves
like `scripted`).

Persistence: the body is a normal entity saved with its chunk (`LuminaryNpc` id, follow target); identity, last
position and memory live in `<world>/data/luminary_npc.dat` (own schema, version 1). On entity load the roster
reconciles: a second body for the same NPC is discarded, a body whose registered predecessor no longer exists takes
over, a body whose definition was removed from the data packs stays but only says the fallback line (logged).

## NPC physical tasks (slice 5)

An NPC task is a typed `NpcAction` with plain-data arguments, validated **before** it is recorded and re-checked by
the executor on every step. Actions (slice 5 + slice 9):

| Action | Arguments | Lifecycle |
|---|---|---|
| `GatherWood` | target log, container, 1 ≤ maxLogs ≤ 16 | `VALIDATED → MOVING_TO_TARGET → BREAKING → (…)* → COLLECTING → MOVING_TO_CONTAINER → DEPOSITING → COMPLETED` |
| `MoveTo` | position | `VALIDATED → MOVING_TO_TARGET → COMPLETED` (within 3 blocks) |
| `Deposit` | container | `VALIDATED → MOVING_TO_CONTAINER → DEPOSITING → COMPLETED` |
| `Withdraw` | container, item id, 1 ≤ count ≤ 64 | `VALIDATED → MOVING_TO_CONTAINER → WITHDRAWING → COMPLETED` (fewer than asked is still completed; the ledger says how many) |
| `Smelt` | furnace, container, input item id, 1 ≤ count ≤ 64 | `VALIDATED → MOVING_TO_CONTAINER → WITHDRAWING (input + just enough fuel, best fuel first) → MOVING_TO_TARGET → LOADING → SMELTING (output pulled into hands as it appears) → MOVING_TO_CONTAINER → DEPOSITING → COMPLETED` |

Validators (in order): supported action, arguments (counts in range, target ≠ container, item id registered),
permission (the body's own world), every position in a loaded chunk and within 32 blocks, the gather target is a block
in `#minecraft:logs`, any container explicitly authorized for this NPC and an `Inventory`, and the inventories make
sense (gather/deposit need a free slot in the container, deposit needs something carried, withdraw needs the item in
the container and a free slot in the NPC's hands; smelt needs a furnace block entity, a furnace recipe for the item,
fuel in the container, a furnace input slot that is empty or holds the same item, two free hand slots). Any failure
returns a stable reason key and records nothing.

Lifecycle (one state per server tick, persisted in `luminary_npc.dat`):

```
VALIDATED → MOVING_TO_TARGET → BREAKING → (next log up: MOVING_TO_TARGET → BREAKING)* → COLLECTING
          → MOVING_TO_CONTAINER → DEPOSITING → COMPLETED | FAILED(reason)
```
- **Breaking** is `ServerWorld.breakBlock(pos, true, npc)` after 20 ticks within 3 blocks: the loot table produces the
  drops as item entities. Nothing is ever granted directly.
- **Collecting** walks to each log item entity in a 6-block radius around the target and absorbs it into the NPC's own
  9-slot inventory (saved in the entity's NBT) once it is within a vanilla-style pickup box; the phase ends when no
  drops have been visible for 10 ticks or after 400 ticks.
- **Depositing** inserts the carried stacks into the authorized container's `Inventory` (merge, then empty valid
  slots). A full container fails the task with `container_full` and the items **stay with the NPC**; the next task
  that reaches a deposit empties them too.
- **Withdrawing** moves stacks of the requested item from the container into the NPC inventory, as many as fit and
  were asked for; the chest keeps the rest.
- **Smelting** is the real `AbstractFurnaceBlockEntity`: the NPC loads slot 0 (input) and slot 1 (fuel) and waits,
  taking slot 2 (output) into its hands as it fills; fuel is chosen best-first and only as much as the cook time
  needs (one coal for three cobblestone). A furnace that stops progressing for `cookTime + 400` ticks ends the task
  with what was produced (`smelt_stalled` if nothing); leftover fuel goes back to the container with the output.
- The **ledger** counts blocks broken, items collected, deposited, withdrawn and smelted, each incremented only by the world
  mutation that just happened, so `deposited ≤ collected ≤ broken` holds and tests assert
  `chest + carried + on-ground == broken`.
- Terminal states are final; a new task gets a new id; an NPC never runs two tasks at once. Extra ticks on a terminal
  task are no-ops. On reload the same task resumes from its persisted state and re-validates the world first.

Failure reasons: validator keys (above) plus `target_changed`, `unreachable` (600 ticks), `container_invalid`,
`container_full`, `container_lacks_item`, `furnace_invalid`, `smelt_stalled`, `nothing_collected`, `cancelled`.

A task remembers who asked for it (`requestedBy`, persisted). When it ends, the NPC writes a `SIGNIFICANT_EVENT`
memory with the outcome and the ledger (superseding the `PROMISE` it made) and tells the requester in chat if they
are online.

Known limitations: the NPC does not open doors or climb; drops that roll out of the 6-block radius are left on the
ground (visible as `collected < broken` in `/luminary npc task status`); the follow order is ignored while a task runs;
no furnace, crafting or farming actions yet.

## Language-model layer (slice 6)

`config/luminary.json`:

```json
{ "dialogueProvider": "scripted",
  "llm": { "provider": "none", "endpoint": "http://127.0.0.1:11434", "model": "qwen2.5:7b",
           "requestTimeoutSeconds": 20, "maxConcurrent": 2, "allowedHosts": [], "logContent": false } }
```
- `provider`: `none` (default, every request fails fast with `UNAVAILABLE`), `ollama` (`POST {endpoint}/api/chat`,
  `stream:false`, JSON schema as `format`, `keep_alive`), `openai` (`POST {endpoint}/chat/completions`, the endpoint
  includes its version prefix such as `https://host/v1`; `response_format: json_object` for structured requests).
- The API key is read **only** from the environment variable `LUMINARY_LLM_API_KEY`; an `apiKey` in the file is ignored
  with a warning. The journal never contains the key, and prompt/reply text only when `logContent` is true.
- Endpoints must be `http(s)` and loopback unless the host is listed in `allowedHosts`.
- Every request runs on provider threads; results are delivered to the server thread via `LlmService.ask`. Limits:
  connect 2 s, request 20 s (configurable), 64 KiB response, `maxConcurrent` in flight (extra requests fail with
  `BUSY`), exponential cooldown 5 s → 5 min after transport/HTTP failures (requests fail at once with `UNAVAILABLE`
  while cooling down; a malformed reply does not count as an outage).
- Journal: `logs/luminary/llm.jsonl`, one line per request (provider, model, purpose, sizes, latency, outcome),
  4 MiB rotation with 3 archives.

The only gameplay caller of this layer is the `model` dialogue provider (slice 8); intent planning will be the second
(ADR-0007).

## NPC memory (slice 7)

Each NPC has a `MemoryStore` persisted in `luminary_npc.dat`. Entries carry a kind, an optional player, an optional
topic key, bounded text (240 chars), timestamps, importance and a **provenance**: `OBSERVED_IN_WORLD` and `AUTHORED`
(trust 3) > `PLAYER_SAID` (2) > `MODEL_INFERRED` (1).

- **Sources**: a chat utterance near the NPC (`PLAYER_SAID`), meetings and first contact (`OBSERVED_IN_WORLD`),
  clues the NPC itself revealed, administrator notes (`AUTHORED`), and every civilization event: first observer of a
  discovery, status changes, technology unlocks, eras, story phases and branches (`NpcMemoryObserver`, provenance
  `OBSERVED_IN_WORLD`). Nothing a model says can enter memory above trust 1.
- **Supersession**: a new entry with the same player and topic key and equal or higher trust replaces the old one
  (kept in a short superseded list); a lower-trust entry is rejected, so a model's guess never overrides a fact.
- **Caps**: 64 entries per player, 4096 per NPC; eviction removes the lowest-scoring entries and never a `PROMISE`.
- **Retrieval** (`MemoryRetriever`): score = recency with a half-life per kind + importance + player match +
  keyword overlap; promises to the current player rank first; another player's small talk is never selected; budgets
  of 12 entries / 1500 characters per interaction. Selected entries have `lastUsed` refreshed.
- **Summarization** (`MemorySummarizer`): past 12 interactions with one player, all but the newest 6 collapse into one
  deterministic `SIGNIFICANT_EVENT` (count, date range, keywords). No model involved.

Dialogue providers receive the retrieved entries and the utterance in the `DialogueRequest`; the `scripted` provider
ignores them, the `model` provider puts them into its prompt.

## Model-backed dialogue (slice 8)

`"dialogueProvider": "model"` plus a configured `llm` block (and `llm.dialogueTimeoutSeconds`, default 8). Or at
runtime: `/luminary npc provider model`.

A turn (right-click or a chat line heard within 8 blocks) becomes **one** structured request:

- system message: the fixed frame (stay in character, answer in the player's language, at most two sentences, know
  only what is listed, never invent discoveries/technologies/story events, never reveal anything outside the list),
  the NPC `persona`, the public context (current era, story phase, chosen branch, clues already public), the retrieved
  memories each tagged `[KIND/PROVENANCE]`, and the **allowed clues**: exactly the clues `StoryController.revealable()`
  returns right now, with their text;
- user message: `<player name>: <utterance>` (or a note that the player approached silently).

The reply must be one JSON object `{"line": string, "clue": id|null, "note": string|null}` (schema forwarded to the
backend). Validation in `ModelDialogueProvider`: the line is required, newlines flattened, cut at 300 characters; a
`clue` that is not in the allowed set (or not an id) is dropped with a log line and the line is still spoken; the
`note` (cut at 160) is stored as `UNVERIFIED_ASSUMPTION` with provenance `MODEL_INFERRED`, so it can never supersede
an observed fact. The reveal itself still goes through `StoryController.reveal` on the server thread (ADR-0005), so
even an accepted clue is refused if the story changed in the meantime.

Failure paths all end in the `scripted` provider's line: model unhealthy or `none` (no request is made), transport or
HTTP error, timeout (`dialogueTimeoutSeconds`), non-JSON or empty reply. The NPC therefore always answers, and the
server thread never waits: `NpcDialogueService.talk` returns a `CompletableFuture<Optional<Turn>>` and finishes
(reveal, memory writes, roster bookkeeping) via the server executor. The model never has a write path to Catalog,
Progression, Story, the world or memory above trust 1 (ADR-0009).

Model lines are sent as literal text (`npc.luminary.model_line` carries the `%s`); scripted lines stay translation
keys. The journal (`logs/luminary/llm.jsonl`) records each dialogue request under purpose `dialogue`; prompt and
reply text only with `llm.logContent = true`.

## From words to work: intent planning (slice 9)

`"intentPlanner": "keyword"` (default) | `"none"` | `"model"` in `config/luminary.json`, or `/luminary npc planner`.

When a player's chat line reaches an NPC (within 8 blocks, or via `/luminary npc intent`), the planner runs **before**
dialogue:

1. **Scan** (`CandidateScanner`, server thread, loaded chunks only): trunk bases (a log with no log below) and
   furnaces within a 33×13×33 box and 32 blocks, the NPC's authorized containers that are loaded inventories with a bounded summary of
   their contents, the player's position, what the NPC carries. Nothing outside this list can be chosen.
2. **Plan**: the `keyword` planner matches word stems (English and Russian) and picks the nearest candidates:
   "chop 3 logs" / "наруби 2 бревна" → `GatherWood`, "put it away" / "сложи в сундук" → `Deposit`, "bring me 10
   cobblestone" → `Withdraw` of an item some container actually holds, "smelt 5 cobblestone" / "переплавь" → `Smelt`
   in the nearest furnace, "come here" / "подойди" → `MoveTo` the player.
   A busy NPC declines; missing trees/containers/items produce a specific refusal line; small talk produces nothing
   and falls through to dialogue. The `model` planner sends one structured request (intent name from a fixed list,
   candidate **indices**, item id, count, reply) and grounds the answer: an index out of range falls back to the
   nearest candidate, an item the container lacks or an unknown intent drops the action but keeps the reply; any
   failure, timeout or non-JSON reply hands the request to the keyword rules.
3. **Assign**: the action goes through `NpcService.assign` and the full validator chain exactly like a command. The
   NPC answers with its line or with `npc.luminary.intent.cannot` + the validator's reason; the words are remembered
   (`PLAYER_SAID`) and the accepted task as a `PROMISE` to that player.
4. **Execute** (the task executor) and **report**: at the end the requester gets "Done: …" or "I could not finish
   (…)", and the memory keeps the outcome.

GameTest `spokenRequestBecomesAValidatedTaskExecutedAndRemembered` runs the whole chain with no model: a mock player
says "could you chop 2 logs for me?", two real oak logs end up in the authorized chest, the promise is superseded by
the completed event.

## Living there: presence, relationships, self-defence, the blade, a day's routine, plans (slice 15)

Everything below is deterministic and lives in `dev.luminary.core.npc.{character,presence,conversation,relationship,
combat,weapon,routine,plan,capability,metrics}`; the Fabric layer (`BehaviourService`, `CombatGoal`, `RoutineGoal`,
`LuminBladeItem`) only observes the world and performs the machines' reactions. No model call is ever made for a
glance, a whisper, a warning, a fight or a return.

- **Character sheet.** The NPC definition's `character` object (`version`, `origin`, `principles`, `speechStyle`,
  `boundaries`, `loreFacts`, `lines`) is the versioned identity. Its `promptLines()` follow the persona in every model
  prompt; its `lines` groups (`gaze`, `gaze_friend`, `gaze_hostile`, `mutter.<activity>`, `warning`, `fight`,
  `deescalate`, `apology_accepted`, `duel_accepted`, `knocked_out`, `recovered`, `weapon_taken`, `weapon_returned`,
  `helped`), authored per language (`en`, `ru`; `presence.language` picks, English is the fallback) feed every
  deterministic channel. Lumin's sheet is `data/luminary/luminary/npcs/lumin.json`. The code never invents lore.
- **Gaze.** A player who looks at the NPC for `gazeMinimumMillis` (600) gets one authored line, then nothing for
  `gazeCooldownSeconds` (45) from that player and `gazeGlobalCooldownSeconds` (10) from anyone; FRIEND and
  WARY/HOSTILE standings use their own line groups. Talking to the NPC counts as acknowledged.
- **Whispers.** Players within `whisperRange` (5 blocks) occasionally hear a gray italic aside derived from what the
  NPC is actually doing (`Activity`: gathering, carrying, smelting, reading, writing, investigating, resting,
  guarded...), at most once per `whisperCooldownSeconds` (90) plus deterministic jitter; silent with nobody near and
  for 30 s after any conversation.
- **Multi-turn context.** The last 8 exchanges with a player (expiring after 10 idle minutes) are sent to the model
  as prior user/assistant messages; persisted with the roster (schema v2).
- **Relationships.** Per player: karma (−100..100, the moral ledger, drifts to zero by `karmaRecoveryPerDay`),
  trust (0..100, never recovers by itself), threat (0..100, halves every `threatHalfLifeMinutes`). Events are
  captured only from authoritative observations: conversation, requested work, gift, attacked / attacked after a
  warning / duel hit (no karma loss), apology, weapon theft (its own heavy event), weapon returned, helped or looted
  while down. Standing FRIEND/NEUTRAL/WARY/HOSTILE goes into prompts and gaze lines; a HOSTILE player is refused
  work. `/luminary npc relations <npc>`.
- **Self-defence.** `CALM → DEFENSIVE_WARNING` on the first hit (shield up in the off hand, a warning line, no
  strike) → `COMBAT` at `attacksBeforeCombat` (3; configure 3..5) hits inside `attackWindowSeconds` (60): the blade
  is drawn and the opponent is struck for `weaponDamageFraction` (0.5) of *their* maximum health when within
  `strikeReach` (3.5); the NPC steps no farther than `holdGroundRadius` (6) from where the fight started and never
  pursues. An opponent farther than `deescalateDistance` (8) for `deescalateAfterSeconds` (20), or one who says
  sorry, ends it (`GUARDED_DEESCALATION → RECOVERY → CALM` after `recoverySeconds`). A player who proposes a duel
  ("let's duel", "дуэль") gets `duelConsentSeconds` (90) in which hits start a fight without warnings and without
  karma loss. No revenge: a finished fight leaves no target.
- **Knockout, never death.** Damage that would reach zero puts the body down instead (lying pose, AI off,
  invulnerable, same entity, no respawn) for `knockoutMinutes` (5) of real UTC time, persisted, so logouts, unloaded
  chunks and restarts do not pause it. Right-click with food shortens the wait by `helpShortensBySeconds` (60); an
  empty hand takes one item from its bag (looting, remembered); sneaking with an empty hand takes the blade.
  `/luminary npc revive <npc>` ends it early.
- **The Holy Sword of Lumin.** `luminary:holy_sword_of_lumin` (a volumetric 22-element item model over a palette texture, the flat `holy_sword_of_lumin.png` kept as the particle/icon fallback; the name shimmers gold–white–sky every frame;
  Better Combat claymore moveset via `data/luminary/weapon_attributes/`): 15 attack damage, 1.7 swings/s, its own Godlike tier above mythic (gold-white bold name and a tier line in the tooltip),
  unbreakable, unenchantable. Three abilities in the style of the pack's unique swords, all server-side:
  **Witness** (passive) records a mark of evidence per hit, up to 7, each +0.5 damage written into the stack's
  attribute modifiers (the tooltip shows it); a mark fades after 30 s without a hit. **Verdict** (use) spends all
  marks in a 12-block beam of light through everything in line for 3 + 2 × marks damage (undead ×1.5 and set on
  fire), everything struck glows 10 s; 6 s cooldown. **Sanctuary** (sneak + use, 3 marks) plants an 8 s circle of
  light, radius 5: players and Lumin inside regenerate and resist, hostile mobs weaken and glow; 30 s cooldown.
  **Unique parameters** (the pack's blue "+x" lines): +0.10 Revelation chance (a hit gives two marks and heals the
  bearer 1) and +25 % holy damage to marked (glowing) targets, applied after the hit as magic damage that ignores
  armour. **Unique effect, Light of the Catalog:** while wielded, hostile mobs within 4 blocks take 1 damage (undead 2)
  every 2 s and glow. **Runes** in the two sockets: the Rune of Clarity (+1 Luck, marks fade twice as slowly) and the
  Netherfused Rune of Embers (every hit kindles 4 s; a hit on a burning target heals 1). The tooltip follows the
  pack's unique-weapon layout: tier, parameters, unique effect, right-click and sneak abilities with ⌛ cooldowns, sockets.
- **The Vestments of the Envoy.** Four pieces (`luminary:vestment_helmet|chestplate|leggings|boots`): 24 armour in
  total (netherite is 20), toughness 4, knockback resistance 0.15 each, unbreakable, Godlike tier, shimmering names,
  their own worn textures and icons. Lumin (`"vestments": true` in his definition) wears a bound set that nothing
  takes: no armour ever replaces a piece, a knocked-out Lumin is not looted of it, a missing piece is restored
  within a second. A free set sits in the creative Combat tab. Unique parameters and effects: the Diadem of Insight
  (+1 luck; blindness, darkness, nausea shrugged off), the Cuirass of the Covenant (fire immunity; a 4-point ward of
  absorption renewed every 20 s), the Greaves of the Procession (+10 % speed; slowness shrugged off), the Sandals of
  the Pilgrim (+5 % speed; no fall damage). Set bonus, the Envoy's Light: below 30 % health, once per 60 s, heal 6,
  Regeneration II, and every hostile thing within 5 blocks is hurled away, hurt 4 and marked. Runes in every piece's two sockets: the Rune of Dawn (+2 max health per piece) and the Netherfused Rune of Cinders
  (whoever strikes the wearer burns 2 s per piece). Blessings: Protection V,
  Thorns III, Fire Protection III and Zenith's Berserker's Fury, Rebounding, Icy Thorns, Reflective Defenses III on
  every piece; Respiration III and Aqua Affinity on the diadem, Swift Sneak III on the greaves, Feather Falling IV,
  Depth Strider III and Soul Speed III on the sandals.
  **Blessings:** every sword the custody mints carries `weapon.blessings` (default: Sharpness VI, Smite VII,
  Looting IV, Sweeping Edge III, Fire Aspect II, Knockback I; from MC Dungeons Weapons Smiting III, Radiance III,
  Thundering III, Committed III, Phantom's Mark III, Leeching III, Swirling III, Guarding Strike III, Echo II; from
  Zenith Scavenger III and Capturing III); ids of mods that are not installed are skipped, so the list is safe on
  any server and the pack's Unrestricted Enchanting keeps the levels.
  In Lumin's own hands the melee blow is the configured half of the target's health, and an opponent who keeps out
  of reach but within 10 blocks is judged by the beam every 6 s instead of being chased. Identity: indestructible,
  bound to its NPC and to a *custody instance id*. The server's
  `WeaponCustody` knows the one valid instance. Taking it from the downed NPC starts a theft of exactly
  `weapon.theftDurationMinutes` (60); at the deadline the weapon returns: the instance is retired, a new one minted,
  every reachable copy (online inventories, dropped items in loaded chunks) removed at once, and any stale copy that
  surfaces later (a chest, an offline inventory) vanishes the moment a player carries it. Only the thief may hold it
  during the hour; a copy in anyone else's hands vanishes. Handing it back early (right-click the NPC with it) counts
  as returning it. Permanent transfer exists exactly once, for a quest the game has not written yet:
  `BehaviourService.transferWeapon(npc, player, reason)` / `/luminary npc weapon <npc> transfer <player> <reason>`
  (op 4); `reclaim` undoes it.
- **Routine.** A fixed day (`DailyRoutine.DEFAULT`: read at the STUDY until 4000, write at the DESK until 7000,
  investigate the SITE until 11500, read again, rest at HOME from 13000) over places set with
  `/luminary npc place <npc> <study|desk|site|home|workshop> <pos>`. The NPC walks there (real pathfinding, never
  teleports), holds a book while reading, a quill-book with an occasional swing while writing, crouches and looks
  around while investigating. Tasks, follow orders, a watching player and self-defence all interrupt it; it resumes
  from the clock, so an unloaded chunk or a long chat changes nothing. Off with `/luminary npc routine <npc> off` or
  `routine.enabled: false`.
- **Plans.** A persistent multi-step `NpcPlan` (steps with preconditions, attempts, the task id that verified them)
  driven by `PlanRunner` through the same validated `assign` path as a command: a running step is never re-issued,
  a step is DONE only when its task COMPLETED, a failure is retried at most twice, an impossible precondition skips
  the step, then the plan fails loudly. Templates: `/luminary npc plan stock <npc> <tree> <chest> <logs>` and
  `/luminary npc plan smelt <npc> <chest> <furnace> <item> <count>`; `status`, `cancel`.
- **Capability allowlist.** `Capability` is the closed list of what the NPC can do; the model may request only
  SPEAK, REMEMBER_NOTE, REVEAL_ALLOWED_CLUE, GATHER_WOOD, DEPOSIT, WITHDRAW, SMELT, MOVE_TO, USE_CONTAINER,
  FORGET_CONTAINER. DEFEND, WEAPON_TRANSFER, STORY_WRITE, PROGRESSION_WRITE, WORLD_EDIT, TELEPORT and ADMIN are
  reachable only from authoritative events and commands. The model intent planner routes every emitted intent name
  through `CapabilityRegistry`; refusals are counted and shown by `/luminary metrics`.
- **Metrics.** `/luminary metrics`: gaze reactions, whispers, attacks, fights, strikes, knockouts, recoveries, thefts,
  returns, purged copies, plan steps, routine arrivals, model refusals.

## Lumin's economy: tasks, points, the shop, the Trial of the Envoy (slice 16)

Right-click Lumin: his dialogue screen (portrait, his points, tasks, shop, a look at his gear; dark navy and gold).
He pays in his own points for the pack's main-story bosses (13 tasks from the FTB Quests `main_story` chapter, from
the Night Lich and the Withered King to the Faceless), sells lightstone dust, the Paladins & Priests holy items and
the one-of-a-kind Seal of the Envoy. Five tasks done with nothing bought open the Trial of the Envoy (five late
bosses without dying once); its reward is his blade with the player's name on it, kept exactly as it was. He then
fights with the Angelic Harp. Rules: `dev.luminary.core.npc.trade`; world side: `TradeManager`; screen:
`TradeScreen`; data: `data/luminary/luminary/quests/`, the `shop` list of `lumin.json`. Full description:
`docs/TRADE.md`.

## Names, gear and reach (slice 14)

- **Addressed by name.** An NPC definition carries `aliases` (`"aliases": ["Lumin", "Люмин"]`; default: the last
  segment of its id). With `conversation.requireName` (default true) a chat line reaches an NPC only when it contains
  one of its aliases, case-insensitively and as a substring ("Люмину," matches), at **any distance** in the same
  world; lines without a name are ignored. With `requireName: false` the old nearby intake applies (`chatRange`,
  0 = unlimited). Prompts carry `PLAYER NAME: <name>` with an instruction to copy it exactly.
- **Reach.** Attending and the looked-at chest both work up to 12 blocks.
- **Gear by right-click.** Holding a tool or armour out to the NPC (right-click with it) hands it over: a tool goes
  into the bag, armour is worn, one piece per slot; a second piece for an intact slot is refused and stays with the
  player, and only a broken piece makes room for the next. Anything else is declined. `/luminary npc gear <npc>`
  lists hand, armour and bag with durability.
- **Tools in use.** Gathering draws the best axe into the hand for the trunk and stows it afterwards; break time is
  vanilla's (`speed / hardness / 30`, bare hands 60 ticks per log, iron axe 10); one durability per block is spent
  through the real `ItemStack.damage`; a broken tool disappears and the next suitable one is used. The NPC's own
  tools and armour are never cargo: deposits, the empty-hands reflex and the intent candidates skip them. Worn armour
  takes damage as on any mob.

## Presence and handover (slice 13)

- **Attending.** When a player within 8 blocks looks at the NPC (their gaze ray crosses its box, line of sight
  clear), it stops walking and faces them, and keeps doing so for two seconds after they look away
  (`AttendPlayerGoal`, below tasks and follow orders). Idle strolling is rarer (one wander roll in 240 ticks).
- **Chest handover by chat.** "use this chest" / "этот сундук твой" / "складывай сюда" while looking at a chest
  (up to 6 blocks) authorizes it for the NPC; "forget this chest" / "не трогай этот сундук" revokes. The keyword and
  the model planner both produce a `Grant`, never a task; `conversation.containerGrantPermission` (default 0, anyone)
  is the operator level required. Without a chest in view the NPC asks which one. The handover is remembered.
- **Latency.** The model is warmed at server start (`purpose: warmup`), kept loaded (`llm.keepAliveSeconds`,
  default 3600), given a bounded context (`llm.contextTokens`, default 4096), and the player sees "…" from the NPC at
  once while a model answer is pending (`conversation.thinkingLine`). A work request costs one model call; small talk
  that the planner answers costs one as well.

## Thaumcraft bridge (slice 12)

See `docs/integration/README.md`: the port publishes plain-map events to the Fabric entrypoint `thaumcraft-observer`;
`ThaumcraftBridge` (core, pure) turns `research_completed`, `scan_completed` and `aspect_discovered` into progression
signals under `signal/thaumcraft/...` and one `OBSERVATION` memory for every NPC, so technologies can require TC4
research and the NPC can talk about what a player learned. Neither mod compiles against the other. The port-side
patch (`docs/integration/thaumcraft-observer-api.patch`) is applied on the port's branch and covered by its GameTests.

## Acting alone: autonomy (slice 10)

Off by default, twice: `"autonomy": { "enabled": false, ... }` in `config/luminary.json` (or `/luminary npc autonomy
server on`) **and** `/luminary npc autonomy <npc> on` per NPC. With both on, the body thinks every
`thinkIntervalTicks` (100) while idle:

1. **Scan** the same candidates the intent planner uses (no player).
2. **Decide** (`AutonomyPlanner`, deterministic, model-free): budget check (`maxDecisionsPerHour`, default 12, a
   sliding window persisted with the roster); reflex *empty hands* (carrying anything → `Deposit` into the nearest
   usable authorized container); then standing orders in the order given. `StockWood(container, minLogs)`: when the
   authorized chest holds fewer log items (`*_log`, `*_stem`) than asked and a trunk is in sight, `GatherWood` the
   shortfall (≤ 16) from the nearest trunk.
3. **Assign** through `NpcService.assign`, i.e. the full validator chain. A refusal, or a later failure of an
   autonomous task, puts its targets on an avoid list for `failureCooldownSeconds` (600) so the NPC never loops on an
   impossible goal. A started task is remembered as a `SIGNIFICANT_EVENT` "Decided on my own (order:stock_wood): …"
   and logged; its outcome supersedes that entry like any task.

Orders live in `luminary_npc.dat` with the switch, the decision log and the avoid list, so a restart changes nothing.
There is no model in this loop on purpose (ADR-0011): self-direction must be predictable and bounded; a model may
later *propose* orders to an administrator, never execute them.

## Layout

```
src/main/java/dev/luminary/core/        pure Java (no net.minecraft / net.fabricmc imports; enforced by a test)
  ids/ events/ catalog/ progression/ story/ npc/ npc/task/ npc/llm/ npc/memory/ persistence/
src/main/java/dev/luminary/fabric/      Minecraft-facing: Luminary (init), LuminaryRuntime, state/, command/, definitions/, npc/, npc/task/, config/, client/
src/test/java/                          JUnit 5
src/gametest/java/                      Fabric GameTests (second Loom mod "luminary-gametest", never shipped)
src/gametest/resources/data/            test-only data pack (test NPCs, a clue gated by a test-only signal, the 13x6x13 "arena" SNBT template)
docs/decisions/                         ADRs
```

## Persistence

The whole `CivilizationState` is JSON (hand-written codec, explicit field names) stored as a string inside vanilla's
persistent-state NBT with a `schemaVersion`. Migrations are ordered JSON-tree steps; a document newer than the running
build is **refused** and the server continues with a non-persisting empty view instead of overwriting it.

## Conventions

English for code, comments, docs and commits. Player-facing text goes through lang files (`en_us`, `ru_ru`).
No secrets in the repository. Never claim a build or test passed without running it.
