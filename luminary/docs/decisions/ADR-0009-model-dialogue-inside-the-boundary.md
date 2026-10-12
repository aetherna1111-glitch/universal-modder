# ADR-0009: The model speaks only through the existing dialogue boundary, and the game never waits for it

Date: 2026-10-12 · Status: accepted

## Decision

1. The language model is one more `DialogueProvider` (`model`). It receives the same `DialogueRequest` and `LineGate`
   as the scripted provider; the gate gained read-only views (`revealableClues()`, `publicContext()`) so the model
   can be told what it may say, never what it must do.
2. One structured request per turn, one JSON reply `{line, clue?, note?}`. The provider validates the reply: bounded
   line, clue accepted only if it is in the allowed set, note downgraded to `MODEL_INFERRED`. The reveal still goes
   through `StoryController.reveal` on the server thread; story, progression, catalog and world have no write path
   reachable from the model.
3. Every failure (no model, cooldown, transport, HTTP, timeout, invalid JSON) falls back to the scripted provider.
   The fallback is a line, not silence, and not an error.
4. `NpcDialogueService.talk` is asynchronous: provider threads produce the response, the server executor finishes the
   turn (reveal, memory, roster, persistence flag). Synchronous providers complete immediately, so the scripted path
   is unchanged in behaviour and the GameTests keep running in one tick.
5. Model lines are literal text (`npc.luminary.model_line`), scripted lines remain translation keys, so the client
   always knows which is which.

## Context

The research set (`AI_NPC_TECHNICAL_RESEARCH.md`, `LUMINARY_SYSTEM_DESIGN_1_20_1.md`) demands an NPC that talks
naturally yet cannot change the civilization's truth, and a server that never stalls on a remote call. Slices 6 and
7 provided the bounded LLM client and the provenance-ranked memory; this slice connects them.

## Alternatives considered

- **Free-text replies parsed with regexes**: fragile; structured output (`format` for Ollama, `json_object` for
  OpenAI-compatible endpoints) gives a stable contract and lets validation be strict.
- **Letting the model pick any clue by text**: would make the story gate advisory. The allowed set is computed by the
  story controller per turn and anything else is dropped before the reveal is even attempted.
- **Blocking the server thread with a short timeout**: even 500 ms per NPC interaction would be felt by every player;
  the asynchronous turn costs one extra hop and no latency on the tick.
- **Writing the model's note as a player fact**: rejected; `MODEL_INFERRED` keeps it visible to the model as "your own
  guess" while world facts and player statements outrank it.

## Consequences

- Dialogue works today with `provider = none` (scripted), with a local Ollama, or with any OpenAI-compatible endpoint
  whose key is in `LUMINARY_LLM_API_KEY`; switching is a config line or one admin command.
- The same request/validate/fallback shape is the template for intent planning (slice 9): the model proposes a typed
  `NpcAction`, the validators decide.
- Prompt size is bounded by the memory budgets and the allowed-clue list; a civilization with many clues open at once
  should still keep the list short through story conditions.
