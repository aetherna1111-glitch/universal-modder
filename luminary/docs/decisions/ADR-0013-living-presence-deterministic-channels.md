# ADR-0013: Living presence through deterministic channels, authoritative events and a custody-identified weapon

Date: 2026-10-14. Status: accepted (slice 15).

## Context

The owner asked for an NPC that lives in the world: reacts to being looked at, mutters while it works, keeps a
relationship with every player, defends itself, can be knocked down and robbed of a unique weapon that always comes
back, follows a day, and carries out multi-step plans. A naive design routes all of this through the language model
(a call per glance, a model deciding to fight, a model deciding who keeps the blade). That is slow (seconds per
decision on a 3B model), unbounded in cost, and unsafe: the model would hold write paths into gameplay state.

## Decision

1. **Every reactive channel is deterministic and authored.** Gaze lines, whispers, warnings, de-escalation, knockout
   and return lines come from the NPC's versioned `CharacterSheet` (`character` in the NPC JSON), chosen by a
   sequence number, rate-limited by `GazeTracker` / `MutterChannel` with injected clock values. The model is used
   only where it was already used: answering what a player said.
2. **Relationships are fed only by authoritative events** (`RelationshipEvent`, captured from damage, inventory moves
   and chat the NPC heard), never by model inference. Karma, trust and threat are separate quantities with
   separate clocks (threat half-life, slow karma recovery, trust that does not recover). Context matters: a hit in
   a declared duel is `DUEL_HIT` (no karma loss); theft of the weapon is its own event.
3. **Self-defence is a pure state machine** (`CombatMachine`): CALM → DEFENSIVE_WARNING (shield) → COMBAT after a
   configurable number of hits in a window → GUARDED_DEESCALATION / RECOVERY → CALM, with hold-ground radius and no
   pursuit; the Fabric `CombatGoal` performs, never decides. Strike damage is a fraction of the *target's* maximum
   health so the "half a player per hit" promise holds for modded health too.
4. **Knockout replaces death.** The entity never dies from damage; at zero it goes down for a real-time span stored as
   a UTC `Instant` (restarts and unloaded chunks cannot pause it). The same entity gets up; the roster still enforces
   one body per NPC, so no duplicate can appear.
5. **The weapon's identity is custody, not an item.** `WeaponCustody` holds the single valid instance id. A theft has
   an exact `returnAt`; the return retires the instance and mints a new one, so every copy anywhere (chests, offline
   inventories, the ground) is stale by construction and removed when next seen (`inventoryTick`) or at once if
   reachable (purge of online inventories and loaded item entities). Permanent transfer is one method and one admin
   command; no quest is invented.
6. **Routine and plans are time-derived and idempotent.** The current routine slot is a function of the world clock,
   so interruptions need no state; plans mark a step DONE only on a COMPLETED task and never re-issue a RUNNING one.
7. **The capability allowlist is explicit.** `Capability` enumerates what exists; `CapabilityRegistry` is the only
   way a model-emitted intent name becomes an action, and it refuses and counts everything else.

## Consequences

- Zero model calls for presence and combat; small-model latency affects dialogue only.
- All timers are testable with injected clocks (`NpcService.setClockOffset` in GameTests; `Instant` arguments in
  unit tests): the five-minute knockout and the one-hour return are verified without waiting.
- A stale blade copy inside a container is inert until picked up; it cannot be used from a chest and is purged on
  pickup. Scanning every container in the world was rejected as too costly for no gameplay gain.
- The NPC state schema moves to v2; v1 documents load unchanged (all new objects are optional).
