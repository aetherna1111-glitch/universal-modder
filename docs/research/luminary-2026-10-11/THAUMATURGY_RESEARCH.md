# Thaumaturgy research: the Thaumcraft 4 port and Luminary

## 1. What exists (VERIFIED from `thaumcraft-fabric` at `e197531`, plus commit messages from newer branches)

- Target MC **1.20.1**, Java 17, Fabric API 0.92.12, Loom 1.6.12. The port's `CLAUDE.md` fixes Thaumcraft **4.2.3.5**
  as the only behavioural authority, forbids TC5/TC6 content, and requires the local decompile under
  `reference/tc4/` for every mechanic.
- Done (per `docs/MIGRATION_STATUS.md`): Step A aspects (48 aspects, object aspects, 457 registrations from TC4
  `ConfigAspects`), Step B wands and foci (rods, caps, 10 foci incl. Warding, warded block, projectiles), 19 GameTests.
- Research package present but, per the status file, "research is empty until Step C": `ResearchRegistry`,
  `ResearchLoader`, `ResearchItem/Category/Page`, `ResearchNotes` (hex minigame), `PlayerKnowledge`, `Knowledge`,
  `KnowledgeState`, `ScanManager`, `WarpType`, `ResearchNetworking`.
- Newer branches (commit messages only, LIKELY): a real-client harness that completed infusion, crucible and golem
  flows ("progression 28/28"), a published v0.1.0, and `createtaumechanics/` (Create ↔ Thaumcraft add-on).
- Not yet ported (status file): crafting (arcane worktable, crucible, infusion — the harness commits suggest some of
  this has since landed), essentia, aura nodes, flux/taint, warp events, golems, tools/armour, worldgen, Outer Lands,
  eldritch bosses, GUIs, FX.

## 2. TC4's own model vs Luminary's model

| Concept | Thaumcraft 4 (per-player) | Luminary (per-civilization) |
|---|---|---|
| Knowing an aspect | `PlayerKnowledge` discovered aspects + aspect pool (research points) | Catalog: an aspect's existence is a **Discovery** (first scanner gets attribution) |
| Research entry | per-player completion via the hex-grid note minigame; prerequisites = other research + aspects | **Verification** of a discovery = a research project; evidence can include "N players completed the TC4 research" |
| Scanning (thaumometer) | per-player scan list; aspects into the pool | Scan event = `DiscoveryObserved(objectId, playerId)` input to the Catalog |
| Warp | per-player permanent/sticky/temporary | Story input: civilization-wide warp pressure as an anomaly signal (Phase V "The Response") |
| Aura nodes (not yet ported) | per-chunk node tiles with vis | Anomaly subsystem candidates: node behaviour deviations as "the Shift" |

Consequence: Luminary must **not** replace TC4's per-player research (that would violate the port's fidelity rule and
its `CLAUDE.md`). It should **observe** TC4 events and **gate** access to TC4 content at the civilization level by
means TC4 already offers or that a thin adapter can add:

1. **Observe**: when a player completes a TC4 research or scans an object, the adapter emits Luminary domain events.
   The port has no event bus today (VERIFIED: no `event` package); the adapter would need either a small
   `thaumcraft.api.event` added to the port (owner/other agent decision) or a Luminary-side mixin into
   `ResearchManager`/`ScanManager` (fragile; version-locked).
2. **Gate**: TC4 research is gated by its own prerequisites. The civilization gate can be realized by making a
   Luminary-granted research entry a prerequisite of TC4 categories ("the Luminarium has verified Thaumaturgy")
   — this is data (research definitions), not code, if the port's `ResearchLoader` reads JSON (VERIFIED file name
   `ResearchLoader.java`; schema UNVERIFIED).

## 3. The version conflict

The port is 1.20.1/Java 17; the owner asked for Luminary on 1.21.1/Java 21. These cannot load together. Three
resolutions, each with consequences:

| Resolution | Cost | Benefit |
|---|---|---|
| **R1. Luminary on 1.20.1** | lose 1.21 features (new data component system, `Identifier.of`, new networking), Java 17 target | immediate compatibility with the TC4 port, Create Fabric 6.0.8.1, Kindred's proven NPC code, the owner's existing modpack ("sborka") |
| R2. Port Thaumcraft to 1.21.1 | a second large port on top of an unfinished one (status: crafting/essentia/nodes/golems incomplete); 1.20.1→1.21.1 changed items (data components), networking, registries, loot/recipe folder names | one modern stack |
| R3. Luminary on 1.21.1, thaumaturgy via a different mod | no faithful TC4 on 1.21.1 exists (VERIFIED search) | none for the owner's vision |

Recommendation: **R1** unless the owner has a specific 1.21.1 requirement. Luminary's domain core should be
pure Java (no Minecraft imports) so that a later move to 1.21.x only touches the thin Fabric layer.

## 4. Integration contract proposal (code to live in Luminary; the port implements the entrypoint)

```java
// dev.luminary.api  (a tiny, stable jar other mods compile against; no Minecraft types in signatures where possible)
public interface LuminaryPlugin {                       // fabric.mod.json: "entrypoints": {"luminary": ["..."]}
    void register(LuminaryRegistrar r);
}
public interface LuminaryRegistrar {
    void discoveryTemplate(DiscoveryTemplate t);         // e.g. "thaumcraft:aspect/ignis" with verification requirements
    void technology(TechnologyDefinition t);             // e.g. "thaumcraft:arcane_worktable" requires era >= TOWN and discovery verified
    void evidenceSource(String id, EvidenceSource s);    // the port reports "player X completed research Y"
}
```
Luminary never imports `thaumcraft.*`; the port's adapter imports `dev.luminary.api`. If the port is not present,
nothing registers and thaumaturgy content is simply absent (graceful degradation).

## 5. Facts to verify before writing the adapter (all UNVERIFIED)

- Current `ResearchLoader` JSON schema and whether research prerequisites can reference an external id.
- Whether `PlayerKnowledge` is stored as a player NBT attachment or a `PersistentState`.
- Whether the harness branches added any event hooks.
- The port's code license.
