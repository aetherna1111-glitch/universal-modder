# Dependencies and compatibility

## 1. The version split (the single most important finding)

| Project | Minecraft | Java | Loader / API | Status |
|---|---|---|---|---|
| Luminary (owner's stated target) | **1.21.1** | 21 | 0.19.5 / 0.116.17+1.21.1 | target, nothing built |
| `thaumcraft-fabric` (TC4 port, the owner's thaumaturgy source) | **1.20.1** | 17 | 0.16.14 / 0.92.12+1.20.1 | active development by another agent |
| `createtaumechanics` add-on (branch `feature/createtaumechanics-and-sborka`) | 1.20.1 | 17 | Create Fabric 6.0.8.1 | active |
| Kindred (KB note, NPC precedent) | 1.20.1 | 17 | 0.15.11 / 0.92.2+1.20.1 | reference only |
| Bundled example `minecraft-gta5-passthrough/mc` | 26.3 | 25 | 0.19.5 / 0.161.0+26.3 | reference only |

**Create Fabric exists only for 1.20.1** (`create-fabric` on Modrinth: game_versions `['1.20.1']`, no 1.21.1
builds; the NeoForge/Forge `create` project has 1.21.1 — VERIFIED). So the owner's existing stack (Thaumcraft port +
Create: Taumechanics + modpack "sborka") is anchored on **1.20.1**, and a 1.21.1 Luminary could not load either of
them. Fabric mods are version-locked (`fabric.mod.json` `depends.minecraft`); there is no cross-version loading.

Options are listed in `OPEN_QUESTIONS.md` Q1. Everything else in this document is written for both versions where
the difference matters.

## 2. Libraries available for Fabric 1.21.1 (VERIFIED on Modrinth, 2026-10-11)

| Mod / library | License | Loaders | 1.21.1 | Role for Luminary | Notes |
|---|---|---|---|---|---|
| GeckoLib 4.9.3 | MIT | fabric, forge, neoforge, quilt | yes | NPC model + animation | depends on Fabric API; Blockbench plugin authoring (LIKELY current) |
| Modonomicon | MIT + CC-BY-4.0 | fabric, forge, neoforge | yes | in-game book UI inspired by the Thaumonomicon; candidate front end for the Catalog/Luminarium | data-driven books; supports multiloader; check its API for dynamic (runtime-unlocked) entries — UNVERIFIED |
| Patchouli | CC-BY-NC-SA-3.0 | all | yes | alternative book UI | **non-commercial** license; check against the server's monetization plans |
| Polymer | LGPL-3.0 | fabric, quilt | yes | server-side-only custom content (items/blocks/entities visible to vanilla clients) | relevant if Luminary wants vanilla-client compatibility |
| fabric-permissions-api (lucko) | MIT | fabric | yes | permission nodes for admin/story commands | works with LuckPerms when present |
| Text Placeholder API (Patbox) | LGPL-3.0 | fabric, quilt | yes | chat formatting | optional |
| owo-lib | MIT | fabric, neoforge, quilt | yes | UI/config/networking helpers | heavy; optional |
| Cloth Config | LGPL-3.0 | fabric, forge, neoforge | yes | config screens | client-side only need |
| Architectury API | LGPL-3.0 | all | yes | multiloader abstraction | only if NeoForge support is ever wanted |
| Create (Fabric) | MIT | fabric, quilt | **no** | industry era | 1.20.1 only |
| FTB Quests | — | — | — | quest UI | Modrinth slugs tried (`ftb-quests`, `ftb-quests-fabric`, `ftb-library`, `ftb-teams`) returned 404; distribution is CurseForge/own maven — UNVERIFIED availability for Fabric 1.21.1 |
| Thaumon | MIT | all | yes | Thaumcraft-style decoration blocks only; no mechanics | useful for the Luminarium's look |

No Thaumcraft gameplay mod exists for Fabric 1.21.1 on Modrinth (search `thaumcraft` with facets fabric+1.21.1
returned only Thaumon and Modonomicon — VERIFIED). The owner's own `thaumcraft-fabric` port is the only candidate.

## 3. Build-tool compatibility (VERIFIED, see `MINECRAFT_FABRIC_1_21_1.md` §1)

- Loom 1.18.x → JVM 25 + Gradle ≥ 9.7; Loom 1.17.x → JVM 21 + Gradle ≥ 9.5; Loom 1.13–1.16 → JVM 21 (Gradle
  minimum not checked, LIKELY 8.13+ for 1.13).
- `thaumcraft-fabric` uses Loom 1.6.12 + Gradle 8.8 + Java 17; it cannot share a Gradle build with a 1.21.1 project
  (different Java release targets and Loom lines). Two separate Gradle builds are mandatory in any layout.
- Running two Gradle builds at once is warned against in the Minecraft playbook ("Never run two Gradle builds at
  once") — VERIFIED as repository guidance.

## 4. Runtime dependencies Luminary itself would add

| Need | Recommendation | Status |
|---|---|---|
| JSON | Gson (shipped with Minecraft) | VERIFIED present in MC; no extra jar |
| HTTP client for LLM | `java.net.http.HttpClient` (JDK 11+) | VERIFIED available on Java 17/21; no dependency |
| Config | Gson-backed JSON file in `config/luminary.json`, written temp+rename | recommendation |
| Tests | JUnit 5 (`junit-bom` 5.11.x) via Gradle `test`; Fabric GameTest for in-world checks | LIKELY works in a Loom project (Kindred did 19 unit tests + 17 GameTests) |
| Entity model/animation | GeckoLib 4.9.3 (optional dependency, isolate behind an adapter) | VERIFIED available |

Avoid: external databases, Kotlin, Architectury, owo-lib — none is justified by a concrete requirement yet.

## 5. Licensing checks still open

- `thaumcraft-fabric` LICENSE says "Original Thaumcraft assets/code are not redistributed by this scaffold"; the
  port's own code license is not stated in `fabric.mod.json` ("See LICENSE") — the owner must pick one before any
  jar is shared beyond the private pack (UNVERIFIED current state on remote branches).
- Thaumcraft 4 itself (Azanor) is not open source; a port's gameplay fidelity is fine, but **no TC4 code or textures
  may be copied** into a public repository (`CONTRIBUTING.md` hard rule; the port keeps `reference/tc4/*` locally).
- Patchouli is CC-BY-NC-SA: incompatible with a monetized server.
