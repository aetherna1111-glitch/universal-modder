# Testing and validation

## 1. Baseline recorded in this session (VERIFIED)

| Check | Result | When |
|---|---|---|
| `uv run --with pytest pytest -q tests` in `universal-modder` | **87 passed, 1 skipped**, 1 Pillow deprecation warning | 2026-10-11, before the write restriction |
| `thaumcraft-fabric` `./gradlew runGametest` | **not run** in this session; status doc records 19/19 on 2026-10-06 | — |
| Any Luminary build | **not run**; the two Gradle configuration attempts failed (Loom 1.18.4 needs JVM 25; Gradle 8.14.3 cannot load Loom 1.17.21) | 2026-10-11 |

## 2. Proposed pyramid for Luminary

| Layer | Tool | Runs in CI | Covers |
|---|---|---|---|
| Unit (pure core) | JUnit 5 via Gradle `test` | yes, seconds | Catalog rules, attribution permanence, verification requirements, Progression prerequisites, Story gating, memory ranking, migrations, JSON round-trip, validators, intent schema parsing, LLM client against a mock HTTP server, architecture import rules |
| Component | JUnit + Fabric Loader off | yes | `IntegrationRegistry` with a fake `modLoaded` predicate; `DisabledProvider` dialogue |
| In-world | Fabric GameTest on a headless server (`-Dfabric-api.gametest`, JUnit XML report) | yes, minutes | commands → state, `PersistentState` save/load, NPC spawn/persistence, gather→deposit task, no-item-creation invariant |
| Server smoke | `./gradlew runServer` with `eula.txt` pre-accepted, wait for "Done", `/luminary status`, `stop` | yes | startup with all flags off, with LLM unreachable, with each integration absent |
| Modpack | the KB technique note "testing a Fabric 1.20.1 mod inside a 450-mod modpack, headless" (branch `kb/technique-testing-a-fabric-mod-inside-a-large-modpack`) | manual/nightly | compatibility with the owner's pack |
| Client | `um win` screenshot harness; the Thaumcraft port's real-client harness pattern | manual | GeckoLib model, UI screens |

Separate the `gametest` source set as the Thaumcraft port does (second Loom mod, not shipped).

## 3. Acceptance criteria per subsystem (examples)

- Catalog: first observer never changes; evidence ids idempotent; verification refused while a dependency is
  unverified; admin override recorded.
- Progression: technology unlock is idempotent; `appliesDiscoveries` flips Catalog status; era definitions from JSON
  validate (unknown prerequisite id → load error with path).
- Story: `setPhase` only via admin actor; `mayReveal` false before phase; auto-advance off by default.
- Persistence: `schemaVersion` N → N+1 migration test with a fixture file; unknown future version refuses load.
- NPC: a GatherWood GameTest ends with `logsInChest == logsRemovedFromWorld`; an adversarial intent (`"action":
  "give_items"`) is rejected by schema; LLM timeout yields the fallback line within the timeout + 1 tick.
- Startup: dedicated server reaches "Done" with `llm.provider=ollama` and nothing listening on 11434.

## 4. Verification discipline (repository rule, VERIFIED in `AGENTS.md`/`CONTRIBUTING.md`)

Never report a build or test as passed unless executed; record what was not run; a field note must list "what you
did not verify".
