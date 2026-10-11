# Existing architecture

## A. `um` CLI (universal-modder) — VERIFIED from source

- `bin/um` resolves symlinks, finds the repo root, and runs `uv run --quiet --project <root> python -m um "$@"`;
  without `uv` it falls back to `python3 -m um` with `PYTHONPATH=<root>`.
- `um/cli.py` builds an `argparse` tree; each group module (`scan`, `fal`, `comfy`, `sprite`, `render3d`, `video`,
  `win`, `backup`, `publish`, `kb`) registers itself with `register(sub)` and uses its module docstring as `--help`.
- `um/kb.py`: notes are Markdown with YAML front matter; `notes(root)` = `root.rglob("*.md")` minus
  `README.md`/`INDEX.md`/`TEMPLATE.md`/`engines/`; `check_note` enforces schema, code-block length (fail > 150 lines,
  warn > 60), secrets and dump patterns; `build_index` regenerates `INDEX.md` + `index.json`; `pr` branches, commits,
  pushes and opens a PR with `gh`.
- `um/publish.py`: pre-release lint for game files, decompiled dumps and leaked keys (`um publish check .` runs in CI).
- `um/win.py` + `um/ps1/*.ps1`: Windows window capture/input; C# 5 constraints documented in `AGENTS.md`.
- `um/backup.py`: save snapshots/restore with same-second collision handling (several tests).

Reusable for Luminary development (not as runtime code): `um kb` for recording field notes, `um publish check` as a
pre-commit lint for secrets, `um win`/`um video` for client-side verification clips, the GameTest/headless patterns
in the knowledge base.

## B. Skills — VERIFIED

`skills/mod-any-game/SKILL.md` is the entry loop (intake → recon → route → lab → source of truth → vertical slice →
assets → verify → showcase → publish). `references/engines/minecraft.md` contains the Fabric/NeoForge/Forge routes
and a list of hard-won 1.20.1 and 26.3 facts (e.g. Fabric `JOIN` fires before the player is in the player list;
delay setup ~10 ticks). `references/safety.md` holds the hard rules (no anti-cheat bypass, no shipping game files).

## C. Bundled Fabric example (`examples/minecraft-gta5-passthrough/mc`) — VERIFIED

- Single Loom project, `splitEnvironmentSourceSets()` (separate `client` source set), two mixin configs, a
  WebSocket server dependency bundled with `include`.
- Shows the repository's own convention for a Fabric project: `gradle.properties` holds the version triple,
  `processResources` expands `${version}` into `fabric.mod.json`, run configs under `loom.runs`.
- Targets Minecraft 26.3 / Java 25 / Gradle 9.7.1, so it is a **layout** reference, not a version reference.

## D. `thaumcraft-fabric` — VERIFIED from the local snapshot (`e197531`)

Initialization order in `thaumcraft.Thaumcraft.onInitialize()`:

```
AspectRegistry → WandRegistry → ResearchRegistry → ScanningManager
→ (CommonLifecycleEvents.TAGS_LOADED, ServerLifecycleEvents.END_DATA_PACK_RELOAD → ObjectAspects.invalidate())
→ ThaumcraftSounds → ThaumcraftBlocks → ThaumcraftBlockEntities → ThaumcraftEntities → FocusRegistry
→ BlockSwapper → ThaumcraftNetworking → ResearchNetworking → ThaumcraftCommand
```

Packages (file counts): `research` 17, `focus/foci` 12, `wand` 10, `item` 8, `client/render` 8,
`entity/projectile` 7, `registry` 5, `focus` 5, `aspect` 5, `client` 3, `block` 3, `network` 2, `mixin` 2,
`block/entity` 2, `util`, `taint`, `mixin/client`, `entity/monster`, `config`, `command`, `aura` 1 each.

Design facts relevant to Luminary:

- **Server-authoritative networking pattern** ("C2S requests, server authoritative") is listed as *keep* in the
  migration matrix. `ResearchNetworking` exists separately from `ThaumcraftNetworking`.
- **Research model** (`PlayerKnowledge`, `Knowledge`, `KnowledgeState`, `ResearchItem`, `ResearchCategory`,
  `ResearchNotes`, `ScanManager`, `WarpType`) is TC4's: per-player research completion, discovered aspects, an aspect
  pool, scanned objects, warp (permanent/sticky/temporary). This is a **per-player** model; Luminary's Catalog is a
  **per-civilization** model. They are complementary, not interchangeable (see `THAUMATURGY_RESEARCH.md`).
- **Tests**: a dedicated `gametest` source set, registered as a second Loom mod (`thaumcraft-gametest`) so test code
  stays out of the shipped jar; run config `gametest` with `-Dfabric-api.gametest` and a JUnit XML report. 19 tests,
  documented as passing on 2026-10-06. This harness layout is worth copying verbatim for Luminary.
- Build-time tools source set (`tools`) using the vanilla DataFixer for legacy id translation.
- Tags-driven aspect tables invalidated on data pack reload; the same invalidation pattern applies to any
  Luminary definition that references registries.

What the newer remote branches add is known only from commit messages (LIKELY): a real-client harness ("crucible
catalyst and infusion", "golem placed, cored and opened by hand (progression 28/28)"), a v0.1.0 release, a
`createtaumechanics/` add-on project built against the port jar and Create Fabric 6.0.8.1 (1.20.1), and modpack
assembly documentation ("sborka": server export inventory, an entity chunk-save race diagnosis).
