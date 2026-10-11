# Dependencies and compatibility for Minecraft 1.20.1 (Luminary target)

All Modrinth and maven data fetched 2026-10-11. "Newest 1.20.1 Fabric file" is the first result of
`/v2/project/<slug>/version?game_versions=["1.20.1"]&loaders=["fabric"]`.

## 1. Core stack (VERIFIED)

| Component | Version | Source |
|---|---|---|
| Minecraft | 1.20.1, Java 17 | piston-meta |
| Fabric Loader | 0.19.5 | meta.fabricmc.net |
| Fabric API | 0.92.12+1.20.1 (2026-09-01) | Modrinth |
| Yarn | 1.20.1+build.10 | meta.fabricmc.net |
| Loom / Gradle | 1.6.12 / 8.8 (port) or 1.10.5 / ≥ 8.12 | Loom module metadata |

## 2. Mods relevant to Luminary — Fabric 1.20.1 (VERIFIED availability and license unless marked)

| Mod | Newest 1.20.1 Fabric file | License | Role | Compatibility status |
|---|---|---|---|---|
| **Thaumcraft 4 port** (`thaumcraft-fabric`, mod id `thaumcraft`) | v0.1.0 release on branch `claude/loving-bohr-pt98hn` (commit message) | not stated ("See LICENSE") | thaumaturgy era, scanning, research, aspects | VERIFIED 1.20.1; **API surface absent** (no event/api package at `e197531`); newer branches not inspected |
| **Create Fabric** | 6.0.8.1+build.1744-mc1.20.1 (2025-12-02) | MIT | industry era signals | VERIFIED; already used by `createtaumechanics` on the owner's branch |
| **GeckoLib** | 4.8.4 (2026-06-20), requires Fabric API | MIT | NPC model/animation | VERIFIED available; Gradle coordinates UNVERIFIED |
| **Modonomicon** | 1.20.1-1.79.3 (2026-01-11) | MIT + CC-BY-4.0 | Catalog/Luminarium book UI | VERIFIED available; dynamic unlock API UNVERIFIED |
| **Patchouli** | 1.20.1-85-fabric (2026-03-08) | CC-BY-NC-SA-3.0 | alternative book | VERIFIED; **non-commercial** |
| **Polymer** | 0.5.20+1.20.1 (2024-05-11) | LGPL-3.0 | vanilla-client content | VERIFIED available; old (2024) — maintenance risk LIKELY |
| **fabric-permissions-api** | 0.3.1 (2024-04-16) | MIT | permission nodes | VERIFIED |
| **Text Placeholder API** | 2.1.4+1.20.1 | LGPL-3.0 | chat formatting | VERIFIED |
| **owo-lib** | 0.11.2+1.20 (2023-09) | MIT | UI/config | VERIFIED; old |
| **Cloth Config** | 11.1.136+fabric | LGPL-3.0 | config screens | VERIFIED |
| **Architectury API** | 9.2.14+fabric | LGPL-3.0 | multiloader | VERIFIED; not needed |
| **Tech Reborn** | 5.8.15 (2026-01-26) | MIT | alternative industry mod | VERIFIED available |
| **Modern Industrialization** | 1.8.6 (2024-07-24) | MIT | alternative industry mod | VERIFIED available |
| **FTB Quests (Fabric)** | `dev.ftb.mods:ftb-quests-fabric` **2001.4.22** on `https://maven.ftb.dev/releases` (not on Modrinth; `2001.x` = the 1.20.1 line) | not read (UNVERIFIED; FTB mods are source-available on GitHub with a custom license — LIKELY) | quest UI, pack progression signals | availability VERIFIED via maven metadata; API UNVERIFIED |
| FTB Library / FTB Teams (Fabric) | 2001.2.13 / 2001.3.2 (same maven) | as above | FTB Quests dependencies | VERIFIED availability |
| **AspectsLib** | 1.20.1 Fabric, MIT, `github.com/0vergrown/AspectsLib` | MIT | "data-driven Aspects from Thaumcraft, attach Aspects to entities" | VERIFIED exists; **overlaps the port's `AspectRegistry`** — do not load both without a compatibility decision (UNVERIFIED conflict) |
| Thaumon | 1.20.1 Fabric | MIT | decoration blocks | VERIFIED |
| Kindred | not public on Modrinth; KB note only | unknown | NPC precedent | code not available; design facts VERIFIED as reported |

Modrinth search for `thaumcraft` with facets fabric + 1.20.1 returned only Thaumon, Modonomicon, Traveller's Boots
and AspectsLib (VERIFIED): the owner's port remains the only faithful TC4 gameplay source on 1.20.1.

## 3. What this stack implies

- One Fabric 1.20.1 server with Fabric API 0.92.12 can load Luminary + Thaumcraft port + Create Fabric 6.0.8.1 +
  GeckoLib 4.8.4 + Modonomicon + FTB Quests (LIKELY; each is individually 1.20.1 — joint testing is the "modpack" tier
  in `TESTING_AND_VALIDATION.md`, and the KB technique note on testing inside a 450-mod pack applies directly).
- Create Fabric and Modonomicon on 1.20.1 are maintained (2025–2026 releases); Polymer and owo-lib builds for 1.20.1
  date from 2023–2024 (LIKELY end-of-line).
- GeckoLib 4.8.x vs 4.9.x: the 4.8 line is the 1.20.1 line; API differences for Luminary's needs (controllers,
  `triggerAnim`) are LIKELY minor but must be read from the 4.8 wiki, not the 4.9 one.

## 4. Unverified items that block a dependency decision

1. GeckoLib Gradle maven URL/coordinates for 1.20.1.
2. Modonomicon: custom unlock-condition registry on Fabric 1.20.1 (else use advancements as the bridge).
3. FTB Quests license and whether its Fabric 1.20.1 API exposes quest-completion events usable from another mod.
4. Thaumcraft port license and current API surface on the active branches.
5. AspectsLib vs the port: whether both register the same ids/tags.
6. Create Fabric's `KineticBlockEntity` stability for reading "network running" signals (internal class).
