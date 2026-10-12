# ADR-0003: Technologies unlock automatically when prerequisites hold

Date: 2026-10-12 · Status: accepted

## Decision

`ProgressionService` unlocks every technology whose prerequisites hold and advances the era whenever the next era's
prerequisites hold, re-evaluating after each relevant domain event (`DiscoveryStatusChanged`, `TechnologyUnlocked`,
`EraEntered`, `SignalChanged`) until a fixed point. Explicit `/luminary progression unlock` exists for operators;
`force` bypasses prerequisites and is recorded (`admin_override:<n>_unmet`, actor UUID).

## Context

Luminary progression is civilization-wide: the owner's design says public, verified knowledge should become capability
"through appropriate technology, materials, infrastructure, equipment, and experimentation", with no arbitrary timers.
Prerequisites already encode those conditions (verified discoveries, infrastructure signals, prior technologies, era).
Requiring an extra "purchase" click would add a chokepoint without adding meaning, and would raise the question of who
is allowed to click. Automatic evaluation also keeps the Catalog → Progression → Catalog(APPLIED) chain deterministic
and crash-safe: every step is idempotent and persisted before the next event is published.

## Alternatives considered

- **Manual research purchase (per player or per Luminarium)**: rejected for the base rule; may return later as a
  *prerequisite type* (e.g. `project_completed`) when the Luminarium project board exists.
- **Evaluate on a timer**: rejected; event-driven evaluation is cheaper and reacts within the same tick.

## Consequences

- A forced unlock cannot apply a discovery that is not VERIFIED (the Catalog refuses); the capability is still granted.
  This is intentional: an admin override grants capability, not knowledge.
- Re-entrant evaluation is coalesced (`evaluating` flag) and bounded (1000 iterations) as a safety net against
  pathological definitions; validation already rejects prerequisite cycles.
- Definitions are replaced on `/reload`; unlocks are never revoked by a reload (persisted state wins). A technology
  removed from data packs stays in the unlock set but grants nothing.
