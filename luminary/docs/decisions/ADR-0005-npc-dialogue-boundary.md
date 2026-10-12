# ADR-0005: NPC dialogue providers choose lines; the story decides what may be said

Date: 2026-10-12 · Status: accepted

## Decision

1. An NPC's speech comes from a data-driven `DialogueScript`. A `DialogueProvider` receives an immutable
   `DialogueRequest` (npc, script, player, memory) and a read-only `LineGate` (conditions, `mayReveal`, `isRevealed`)
   and returns at most one `DialogueResponse`. It has no reference to world, story or progression mutators.
2. `NpcDialogueService` is the only caller. When a response carries a clue it calls `StoryController.reveal`; if the
   story refuses, the response is replaced by the fallback line. An empty or throwing provider also yields the fallback.
3. Two providers ship: `scripted` (default, deterministic) and `disabled` (always empty). A future model-backed
   provider plugs into the same interface and inherits the same boundary.
4. NPC identity and memory are persisted in a separate `PersistentState` (`luminary_npc.dat`, own schema line) with a
   roster that allows exactly one body per NPC definition.

## Context

The owner's requirements: the model must never advance the plot or reveal restricted information; dialogue must work
without an LLM; the server stays authoritative. Putting the reveal inside the service (not the provider) and giving
providers only a gate makes the rule structural rather than a convention.

## Alternatives considered

- **Provider performs the reveal itself**: a buggy or malicious provider could leak; rejected.
- **Clue lines repeat after reveal**: makes the NPC a broken record; revealed clues are available through
  `/luminary story clues` (and later a book), so the NPC moves on.
- **NPC memory inside the civilization state**: would rewrite the whole civilization document on every conversation;
  separate state per ADR-0002.

## Consequences

- Tests can prove the boundary: `NpcDialogueServiceTest.providerCannotLeakAClueTheStoryRefuses`.
- Dialogue content is localizable (`text` is a lang key) and data-pack extensible; conditions reuse story/progression
  types, so no new condition language exists.
- The entity carries only its definition id and follow target; everything else is recoverable from the roster even if
  the entity is lost, and a lost body can be re-spawned without losing memory.
- Not covered yet: physical tasks, animation/model, model-backed providers, player-specific memory beyond counts.
