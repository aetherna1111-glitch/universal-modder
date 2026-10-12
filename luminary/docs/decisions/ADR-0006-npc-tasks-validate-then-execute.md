# ADR-0006: NPC physical tasks are typed actions, validated before execution, executed through real world APIs

Date: 2026-10-12 · Status: accepted

## Decision

1. A physical task is an `NpcAction` record with plain-data arguments. The only entry point is `NpcService.assign`,
   which runs the `ActionValidator` chain against a `WorldActionContext` built from live server state and records an
   `NpcTask` only on success. Commands and any future planner go through this method; the executor is not reachable
   with an unvalidated action.
2. `TaskExecutor` advances the task one explicit `TaskState` per server tick and re-checks the target and the
   container on every step. World changes use vanilla APIs (`breakBlock` with drops, item entities, `Inventory`
   insertion). The NPC carries collected items in its own `SimpleInventory` saved in entity NBT.
3. The `TaskLedger` counts only completed world mutations. Deposit failures keep the items with the NPC; nothing is
   ever dropped on purpose or created.
4. Authorization for deposit containers is an explicit per-NPC list of block positions in the roster (admin command),
   persisted; an unauthorized or non-inventory destination fails validation and, if it changes later, fails the task.
5. Task state is persisted inside the existing `luminary_npc.dat` record (no new schema line); a reload resumes the
   same task id from its saved state.

## Context

The owner's requirements: real actions, no resource generation, explicit preconditions and terminal states, no
duplication through retries or reloads, untrusted action data. The research design (`AI_NPC_TECHNICAL_RESEARCH.md`
§3) proposed a larger action set; this slice implements the one composite action the owner asked for and the layer the
others will reuse (validators, context, ledger, phases, persistence).

## Alternatives considered

- **Instant mining and direct item grants** for simplicity: rejected; they are exactly the shortcut the design forbids.
- **Actor-surrogate player** (as Kindred did) to reuse vanilla interaction code: deferred; `breakBlock` with the NPC as
  breaking entity gives correct loot without a fake player.
- **Separate task file per NPC**: unnecessary at this size; the roster record already persists per NPC.

## Consequences

- GameTests can assert the accounting invariant from outside: chest + carried + on-ground == blocks broken.
- A test-only 13x6x13 arena template (`gametest/structures/arena.snbt`) isolates each task test; the template's layer 0
  is placed at relative y = 1 (the structure block occupies y = 0).
- Found and fixed during the slice: a move-phase check against the original target instead of the current trunk block
  (multi-log tasks failed), and a pickup range based on center distance that could leave a drop at the far edge of the
  adjacent block uncollected until the collect timeout (intermittent `collected < broken`). Pickup now uses the
  vanilla mob rule (bounding box grown by one block) and a move-control nudge when the path has already ended.
