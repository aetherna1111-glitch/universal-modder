# The Ascending Intelligence: NPC technical research

Status: design + evidence. No NPC code exists for Luminary. The strongest evidence base is the Kindred field note
in this repository (`knowledge/games/minecraft/kindred-local-llm-ai-companion-fabric.md`, Fabric **1.20.1**, status
`working`, 19 unit tests + 17 GameTests reported) — a different project, but the same problem class.

## 1. Non-negotiable boundary

```
player chat / world observation ──► Context builder (server thread, bounded)
      ──► LLM provider (worker thread, timeout, size cap) ──► JSON "Intent" (allow-listed schema)
      ──► Validator (server thread: permissions, prerequisites, resources, story rules, rate limits)
      ──► Planner → typed Task → Executor (ticks on the entity; real world actions)
      ──► Animation state published to clients
```
The model only ever produces **dialogue text** and an **Intent** from a closed set. It cannot name commands,
block ids to spawn, or progression ids to unlock, because the schema has no such fields. Everything that mutates the
world is a `Task` the executor already knows how to perform under vanilla rules. This mirrors Kindred's "the model
returns an Intent — an allowlisted data object, never arbitrary code" (VERIFIED as reported).

## 2. Components (independently testable)

| Component | Package | Pure Java? | Persisted | Notes |
|---|---|---|---|---|
| Personality & dialogue | `core.npc.persona` | yes | no (config) | system prompt fragments, style rules, forbidden topics; locale `ru`/`en` |
| Memory | `core.npc.memory` | yes | yes | §4 |
| Context retrieval | `core.npc.context` | yes | no | selects ≤ N memory items + world snapshot into a token budget |
| Intent planner | `core.npc.plan` | yes | task state yes | Intent → `Task` graph with preconditions |
| Validation | `core.npc.validate` | yes | no | §3 |
| Task executor | `fabric.npc.exec` | no | task progress yes | navigation, block break, inventory, container I/O |
| Animation | `fabric.npc.client` (+ GeckoLib adapter) | no | no | maps `TaskPhase` → animation |
| Narrative controller | `core.story` (shared) | yes | yes | `StoryController.mayReveal` is the only clue gate |
| Persistence | `fabric.npc.state` | no | yes | entity NBT for body; `PersistentState` roster for identity |
| LLM providers | `fabric.npc.llm` | yes (JDK HttpClient) | no | §5 |

## 3. Typed operations (the only world-changing surface)

```java
public sealed interface NpcAction permits NpcAction.Say, NpcAction.MoveTo, NpcAction.GatherWood, NpcAction.CollectDrops,
        NpcAction.Deposit, NpcAction.Withdraw, NpcAction.MilkCow, NpcAction.OperateFurnace, NpcAction.Idle {
    record Say(String text, UUID toPlayer) implements NpcAction {}
    record MoveTo(long posPacked, int toleranceBlocks) implements NpcAction {}
    record GatherWood(int targetLogs, int maxRadius) implements NpcAction {}
    record CollectDrops(int maxRadius) implements NpcAction {}
    record Deposit(long containerPos, Set<LuminaryId> itemFilter) implements NpcAction {}
    record Withdraw(long containerPos, LuminaryId item, int count) implements NpcAction {}
    record MilkCow(UUID cow) implements NpcAction {}
    record OperateFurnace(long furnacePos, LuminaryId input, LuminaryId fuel) implements NpcAction {}
    record Idle(int ticks) implements NpcAction {}
}
public record ValidationResult(boolean ok, String reasonKey) {}
public interface ActionValidator { ValidationResult validate(NpcAction a, NpcContext ctx); }
```
Validators (composable, each a few lines, each unit-tested): `WithinHomeRadius`, `NotProtectedBlock` (base
protection radius — Kindred gotcha 6: the companion dismantled base walls), `HasTool`, `InventoryHasRoom`,
`ContainerIsAllowed` (only containers tagged/claimed for the NPC), `RateLimit` (actions per minute),
`StoryAllows` (clue checks for `Say`), `NoItemCreation` (executor invariant: every item in the NPC inventory has a
provenance record: picked up, withdrawn, crafted from inputs).

Task definition contract (owner requirement): explicit `preconditions()`, `steps()`, `progress()`, `succeeded()`,
`failed()`, `recover()`; a task with no progress for N ticks fails with a reason (Kindred gotcha 5: stuck jobs with
no notification — fix by always emitting `NpcTaskFailed(reason)` and speaking a short line).

## 4. Memory model

```java
public enum MemoryKind { STABLE_FACT, RECENT_INTERACTION, SIGNIFICANT_EVENT, PROMISE, RELATIONSHIP, OBSERVATION,
                         DISCOVERY_CLUE, UNVERIFIED_ASSUMPTION, NARRATIVE_SECRET }
public record MemoryEntry(UUID id, MemoryKind kind, Optional<UUID> player, String text, Instant at, Instant lastUsed,
                          double importance /*0..1*/, Provenance provenance /* OBSERVED_IN_WORLD | PLAYER_SAID | MODEL_INFERRED */,
                          Optional<LuminaryId> secretCondition /* only for NARRATIVE_SECRET */) {}
```
- **Creation**: observations come from server code (join/leave, trades, task outcomes, discoveries) with
  `Provenance.OBSERVED_IN_WORLD`; player statements are `PLAYER_SAID`; anything the model concludes is
  `MODEL_INFERRED` and stored as `UNVERIFIED_ASSUMPTION` until confirmed by an observation.
- **Retrieval**: score = w1·recency + w2·importance + w3·playerMatch + w4·keywordOverlap (plain Java; no vector DB
  needed at this scale). Budget: ≤ 12 entries + ≤ 6 recent turns per request. Kindred shipped 12 recent events in
  NBT and worked (VERIFIED as reported).
- **Contradiction**: a new OBSERVED fact that conflicts (same `(player, topicKey)`) supersedes and tombstones the
  old entry (kept 7 days for debugging).
- **Summarization**: when `RECENT_INTERACTION` for a player exceeds N, a deterministic summarizer (not the LLM, to keep
  it reproducible) collapses them into one `SIGNIFICANT_EVENT`; an LLM-produced summary may be stored only as
  `MODEL_INFERRED`.
- **Secrets**: `NARRATIVE_SECRET` entries are never placed in the prompt unless `StoryController.mayReveal(condition)`
  is true at request time; the clue text itself is authored, not generated. Free-form dialogue is not lore truth.
- **Persistence**: one `PersistentState` (`luminary_npc.dat`) holding JSON; per-player caps (e.g. 64 entries) and a
  global cap; eviction by lowest score, never of PROMISE entries until fulfilled/expired.

## 5. LLM provider abstraction and Ollama facts

```java
public interface LlmProvider {
    CompletableFuture<LlmResponse> complete(LlmRequest req, Duration timeout);   // never blocks the caller
    String id();  boolean healthy();
}
public record LlmRequest(List<Message> messages, Optional<String> jsonSchema, int maxOutputTokens, double temperature) {}
public record LlmResponse(String text, Optional<JsonObject> structured, Usage usage) {}
```
Providers: `OllamaProvider` (local), `OpenAiCompatibleProvider` (any `/v1/chat/completions` server, incl. llama.cpp,
vLLM, LM Studio — LIKELY same wire format), `DisabledProvider` (deterministic fallback: scripted lines + rule-based
intents; the server must work with this alone).

Ollama `/api/chat` (VERIFIED from `ollama/ollama/docs/api.md`, fetched 2026-10-11): parameters `model`,
`messages[{role,content,images?,tool_calls?}]`, `tools`, `think` (boolean or `"low"|"medium"|"high"|"max"`),
`format` (`"json"` **or a JSON schema object** → structured outputs), `options` (e.g. `temperature`, `num_ctx`,
`num_predict`), `stream` (set `false` for one object), `keep_alive` (default `5m`; `0` unloads). Default endpoint
`http://127.0.0.1:11434`. Use `format` = the Intent JSON schema for action turns and plain text for chat turns, as
Kindred did.

Operational rules: loopback-only endpoint by default (configurable allow-list), no redirects, response size cap
(e.g. 64 KiB), connect/read timeouts (2 s / 20 s), at most 1 in-flight request per NPC and a global semaphore
(VRAM), exponential cooldown after failures, generation ids to drop stale replies (Kindred gotcha: config revision
checks). Hardware: Kindred reported acceptable play with `qwen2.5:7b` on an RTX 3060 12 GB (anecdotal; **latency and
quality not measured** — VERIFIED as "not verified" in that note). Luminary should measure p50/p95 latency and
schema-validity rate before choosing a model; a dedicated server without a GPU should plan for a remote provider or
the `DisabledProvider`.

## 6. Threading model

- Build the prompt and world snapshot **on the server thread** (immutable record), hand it to a single daemon
  executor, and post the result back with `server.execute(Runnable)` (VERIFIED API: `MinecraftServer` is a
  `ReentrantThreadExecutor`; LIKELY exact method name `execute`). Never touch `World`, entities or inventories from
  the worker.
- Per-tick budgets: pathfinding attempts ≤ 1/s per NPC (Kindred), sensor scans N positions/interval, no chunk
  loading by the NPC (`world.isChunkLoaded` checks before any position query — LIKELY API).
- Distance policy: when no player is within R (e.g. 64 blocks), the entity ticks a cheap state machine (no sensors,
  no LLM, no pathing) and tasks are **paused**, not simulated; nothing completes offscreen. If the chunk unloads,
  the body unloads with it and the `PersistentState` roster keeps identity, last position and task (Kindred gotcha 12:
  prevents duplicate spawn).

## 7. Entity, animation, model

- Entity: a `PathAwareEntity` subclass registered with `EntityType.Builder` + `FabricDefaultAttributeRegistry`
  (see `MINECRAFT_FABRIC_1_21_1.md` §6); `setPersistent()`; `setCustomName`; no natural spawn, one instance per
  server by default (roster enforces).
- Actor surrogate for vanilla interaction hooks (unconnected `ServerPlayerEntity`) is **optional and risky** — it
  gave Kindred crafting and tool damage for free but breaks with session-aware mods. Prefer explicit executor code
  (break block with proper drops via `world.breakBlock(pos, true)` after `canHarvest` checks — LIKELY API) and only
  adopt a surrogate for a specific blocker.
- Animation: GeckoLib 4.9.3 (Fabric 1.21.1, MIT — VERIFIED) with Blockbench authoring; the server syncs `TaskPhase`
  via `DataTracker`, the client picks animations; failure/interrupt phases have their own clips so a failed task never
  shows a success animation. Vanilla keyframe animation is the no-dependency fallback.

## 8. Prototype ladder (verification per rung)

| Rung | Deliverable | Verification |
|---|---|---|
| 1 | persistent entity, `/luminary npc spawn|despawn` | GameTest: spawn, save/load world, one instance |
| 2 | follow/move-to, look-at | GameTest: reaches target within N ticks |
| 3 | scripted dialogue (DisabledProvider) | unit test on responder; GameTest on chat hook |
| 4 | roster `PersistentState` | GameTest: unload/reload chunk → same UUID |
| 5 | memory store + retrieval | unit tests (ranking, caps, contradiction) |
| 6 | GatherWood → CollectDrops → Deposit | GameTest with a tree and a chest: logs end in the chest, none created |
| 7 | Intent JSON schema + validators | unit tests incl. adversarial intents (unknown action, out-of-radius) |
| 8 | Ollama provider | unit test against a local mock HTTP server (timeouts, size cap, bad JSON) |
| 9 | clue gating | unit test: secret never in prompt before phase |
| 10 | GeckoLib model | client screenshot harness (`um win screenshot`) |

## 9. Open technical risks

- Pathfinding across player-built cities (doors, stairs, trapdoors) is the usual failure point; budget time for
  `Goal` tuning and door-opening (`MobNavigation.setCanOpenDoors` — LIKELY).
- Multiplayer: who may command the NPC? Proposal: anyone may talk; only Luminarium members may assign tasks; admins
  may override. Needs the owner's call (`OPEN_QUESTIONS.md` Q7).
- Russian player-facing text with a small local model: quality varies by model; test `ru` outputs explicitly.
