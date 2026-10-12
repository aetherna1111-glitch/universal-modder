# ADR-0008: NPC memory is typed, provenance-ranked and fed only by observed facts, player speech and admins

Date: 2026-10-12 · Status: accepted

## Decision

1. `MemoryEntry` carries a `Provenance` with a trust level. Same-topic entries for the same player are superseded only
   by equal or higher trust; a model's inference (trust 1) can never replace what the world or an administrator
   recorded (trust 3) or what the player said (trust 2).
2. World facts enter memory through `NpcMemoryObserver`, which subscribes to the domain event bus and writes
   `OBSERVED_IN_WORLD` entries for every defined NPC. There is no API for a dialogue provider to write memory.
3. Retrieval is budgeted (12 entries, 1500 characters) and deterministic; summarization is deterministic and
   model-free, producing `OBSERVED_IN_WORLD` summaries of counts and dates only.
4. Memory lives in the NPC roster record (`luminary_npc.dat`); the codec gained an optional `memories` array, no
   schema bump.

## Context

The research set required memory that distinguishes observed facts from speculation, bounded context per request,
contradiction handling, summarization and eviction (`AI_NPC_TECHNICAL_RESEARCH.md` §4). The owner's rule that free-form
AI dialogue is never the source of truth for lore translates directly into the trust ordering.

## Alternatives considered

- **Embedding-based retrieval**: needs a model or a vector library; at this scale keyword overlap plus recency,
  importance and player match is enough and fully testable.
- **Model-written summaries**: non-deterministic and could invent content; deferred until a provider exists, and even
  then such summaries would be stored as `MODEL_INFERRED`.
- **A separate persistent state per NPC**: not needed yet; caps keep the store small.

## Consequences

- Slice 8 can build a model-backed dialogue provider with true context and no write path.
- Chat intake adds a second conversation channel; the message is observed, never consumed.
- Admin `memory note` entries are the mechanism for the NPC's teaching role until a richer knowledge source exists.
