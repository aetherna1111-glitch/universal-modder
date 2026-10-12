# ADR-0010: Planners choose among server-scanned candidates and never bypass the validators

Date: 2026-10-12 · Status: accepted

## Decision

1. A player's words are turned into work by an `IntentPlanner` that may return at most one typed `NpcAction`. The
   planner's vocabulary is the `IntentCandidates` the server scanned around the NPC body right before planning:
   trunk bases, authorized containers with their contents, the player's position. A planner cannot name a coordinate
   or an item outside that list; the model planner speaks in indices, and anything ungrounded is dropped.
2. The planned action is handed to `NpcService.assign`, the same entry point commands use, and passes the full
   validator chain. Planners propose; validators decide; the executor acts.
3. The default planner is deterministic (`keyword`, English and Russian stems). It is the fallback of the model
   planner and the reason the whole chain is covered by a GameTest on a server with no model.
4. New actions (`MoveTo`, `Deposit`, `Withdraw`) follow the slice-5 pattern: plain-data arguments, validators per
   action, explicit lifecycle states, ledger increments only on world mutation, persisted with the roster.
5. Tasks remember their requester. Accepting a request writes a `PROMISE` memory; the terminal state writes a
   `SIGNIFICANT_EVENT` with the ledger that supersedes the promise (same topic key, same trust), and the requester is
   told in chat.

## Context

The owner asked for an NPC that understands requests and acts in the world. The research set insists the model is
never the authority over what happens in the world; slice 5 built validation and execution; slice 8 bounded the
model's speech. This slice bounds the model's *proposals* the same way.

## Alternatives considered

- **Let the model emit coordinates and item ids freely**, trusting the validators: validators would catch invalid
  targets, but the model would be able to aim the NPC at any loaded log or chest it hallucinates near a player;
  indexing into a scanned list keeps the planner's reach equal to what the server already deemed relevant.
- **Model-only planning**: no deterministic path means no test without a model and no behaviour when the model is
  down; the keyword rules are small, predictable and sufficient for the current four actions.
- **One combined dialogue+intent request**: cheaper, but the two outputs have different validation and the planner
  must run before dialogue so that a refused action can be explained; two bounded requests are simpler to verify.

## Consequences

- The autonomy loop (next) can reuse the planner contract: the NPC's own goals become `IntentRequest`s with no player.
- Adding an action means: record in `NpcAction`, validator branches, executor phases, codec branch, a keyword rule and
  a planner intent name. The GameTest arena already covers inventories and movement.
- The candidate scan is bounded (box, counts, item summary size); very dense forests see only the 8 nearest trunks.
