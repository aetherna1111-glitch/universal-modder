# Thaumcraft port bridge

Luminary learns what players discover in the Thaumcraft 4 port (`thaumcraft-fabric`, Minecraft 1.20.1) without either
mod compiling against the other. The port publishes **plain maps** to a Fabric entrypoint; Luminary declares that
entrypoint in its `fabric.mod.json`:

```json
"entrypoints": { "thaumcraft-observer": ["dev.luminary.fabric.integration.ThaumcraftObserverEntrypoint"] }
```

The entrypoint type is `java.util.function.Consumer<Map<String, Object>>`, so the only shared vocabulary is the JDK and
the map keys below. Both mods load fine without the other.

## Event contract (port → observers)

Fired on the server thread, after the port recorded the change. Values are only `String`, `Number`, `Boolean`,
`java.util.UUID`, `List` or `Map` of those.

| `type` | keys | Luminary effect |
|---|---|---|
| `research_completed` | `player` (UUID), `playerName`, `key` (TC4 research key), `warp` (int) | `signal/thaumcraft/research/<slug(key)>` += 1, `signal/thaumcraft/research_total` += 1, NPC observation |
| `scan_completed` | `player`, `playerName`, `scanType` (`item`/`entity`/`phenomena`), `target` (id or phenomenon), `prefix` (`@`/`#`), `aspects` (tag → points) | `signal/thaumcraft/scan/<slug(target)>` += 1, `signal/thaumcraft/scans` += 1, NPC observation |
| `aspect_discovered` | `player`, `playerName`, `aspect` (tag) | `signal/thaumcraft/aspect/<slug(tag)>` = max(1), NPC observation |

`slug` lower-cases, turns `:` into `/` and any other unknown character into `_` (`ThaumcraftBridge.slug`). Unknown
types or malformed events are ignored and logged at debug level. Signals are plain progression signals, so a
technology can require them: `{ "type": "signal", "signal": "luminary:signal/thaumcraft/research/thaumometer", "min": 1 }`.

## Port side

`thaumcraft-observer-api.patch` in this folder adds to the port: `thaumcraft.api.observer.ThaumcraftObserver` and
`ThaumcraftObservers` (a Fabric `Event` for mods that compile against the port, plus delivery to every
`thaumcraft-observer` entrypoint, each receiver isolated by try/catch), and three one-line publications in
`ResearchManager.completeResearchUnsaved` (server side), `ScanManager.completeScan` and
`ScanManager.checkAndSyncAspectKnowledge` (first discovery). Nothing in TC4 behaviour changes: observers are notified
after the fact and cannot influence the outcome.

Status (2026-10-13): the patch applies cleanly to commit `e197531` of the port but **could not be compiled or
tested there**, because that commit itself does not compile (its `Thaumcraft.java` references a `ScanningManager`
class that does not exist, and `PlayerKnowledge`/`ThaumcraftCommand` are mid-migration against `AspectList` and
`ResearchManager`). The patch should be applied once the port's research step (Step C) compiles again, then verified
with the port's own GameTests plus one that registers a `ThaumcraftObservers.EVENT` listener and calls
`ResearchManager.completeResearch` for a mock player. The Luminary side is covered by unit tests
(`ThaumcraftBridgeTest`) and a GameTest (`IntegrationGameTests`) that drives the entrypoint directly.
