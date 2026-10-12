# ADR-0011: Self-directed behaviour is deterministic, doubly opt-in and budgeted

Date: 2026-10-12 · Status: accepted

## Decision

1. Autonomy is off until both the server-wide flag (`autonomy.enabled`) and the NPC's own switch are on. Either one
   off means the NPC only ever acts on a command or a request.
2. The autonomy planner is deterministic and model-free: reflexes (empty hands → deposit) and persisted standing
   orders (`StockWood`), evaluated every think tick while idle. The model has no place in this loop; it may later
   propose orders to an administrator through the intent layer.
3. Every self-started task passes through `NpcService.assign` and the validators, exactly like a command.
4. A sliding budget (12 decisions per hour by default) bounds self-started tasks; it is persisted so a restart does
   not refill it. Refused and failed targets are avoided for a cooldown (10 minutes by default).
5. Every decision is remembered (`SIGNIFICANT_EVENT`, superseded by the task's outcome) and logged, so an
   administrator can always reconstruct why the NPC did something.

## Context

The owner's goal is an NPC that lives in the world, not a vending machine. The research set's safety rules for
autonomous agents in a shared world (bounded, observable, interruptible, no hidden goals) translate into the
switches, the budget, the avoid list and the memory trail. Slices 5 and 9 supplied actions, validators and candidates.

## Alternatives considered

- **Model-driven autonomy** ("decide what to do next" prompts): unbounded, unpredictable on a survival server, hard
  to test without a model; the current loop is fully covered by a GameTest with no model.
- **Opt-out instead of opt-in**: an NPC that starts cutting trees after an update would surprise every server owner.
- **Budget in memory only**: a restart would reset it; persisting the decision log costs a few timestamps.

## Consequences

- New standing orders follow the slice pattern: a record in `StandingOrder`, a branch in the planner, a codec
  branch, a command and a lang key. Candidate orders: keep a furnace fed, keep a farm harvested, patrol.
- Autonomous tasks have no requester, so nobody is told in chat; the memory and the log carry the trail.
- Dense forests are consumed nearest-first; a future order can hold a replant step once a `Plant` action exists.
