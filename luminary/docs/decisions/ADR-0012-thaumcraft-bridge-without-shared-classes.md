# ADR-0012: The Thaumcraft bridge is a map-typed Fabric entrypoint, not a compile-time dependency

Date: 2026-10-13 · Status: accepted

## Decision

1. The port notifies observers through a Fabric entrypoint key (`thaumcraft-observer`) typed with a JDK interface
   (`java.util.function.Consumer<Map<String, Object>>`). Events are maps of JDK values with a documented contract.
2. Luminary declares that entrypoint and translates events in a pure core class (`ThaumcraftBridge`) into
   progression signals and NPC observations; the Fabric wrapper only applies them on the server thread.
3. The port-side change is additive and after-the-fact: three publications placed after the knowledge change is
   recorded; receivers are isolated by try/catch and cannot alter TC4 behaviour.
4. The port patch lives in Luminary's `docs/integration/` until the port compiles again; it is not committed to the
   port while it cannot be built and tested there.

## Context

The port's license is not yet declared (its `fabric.mod.json` says "See LICENSE" and the file names none), so
Luminary cannot compile against the port jar in a public CI; the port must not depend on Luminary either. Fabric
resolves entrypoints by key and type at runtime, and JDK types are shared by definition.

## Alternatives considered

- **`compileOnly` on the port jar**: blocked by the license question and by the port's unstable build.
- **Luminary API jar the port implements**: inverts the dependency; the port's maintainers would have to track
  Luminary releases.
- **Reflection or a shared static registry**: fragile and needs class names on both sides anyway.
- **Vanilla mechanisms (advancements, scoreboard)**: would entangle player-visible systems with an integration.

## Consequences

- Any other mod can feed Luminary the same way by declaring the entrypoint and sending the documented maps; and any
  other mod can observe the port by the same key.
- Signals are the only progression effect for now; mapping specific research keys to Catalog discoveries is data
  (prerequisite `signal`) rather than code.
- When the port's research step compiles, applying the patch and adding its GameTest is a small, separate change.
