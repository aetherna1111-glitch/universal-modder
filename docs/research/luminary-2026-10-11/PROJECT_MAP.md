# Project map

Two repositories are checked out side by side under `/home/user` in this session. Both belong to the GitHub account
`aetherna1111-glitch`. Facts below were read from the working trees and from the GitHub API on 2026-10-11.

## 1. `universal-modder` (fork of `rehan-remade/universal-modder`)

**What it is (VERIFIED).** A Python 3.10+ CLI (`um`) plus Agent-Skills documents plus a Markdown knowledge base, for
AI coding agents that mod PC games. It is **not** a Minecraft mod and has **no Gradle build at the root**. The only
Fabric project inside it is the bundled example `examples/minecraft-gta5-passthrough/mc` (Minecraft **26.3**,
Loader 0.19.5, Loom `1.18-SNAPSHOT`, Fabric API `0.161.0+26.3`, Java 25, Gradle 9.7.1 wrapper).

| Area | Path | Notes |
|---|---|---|
| CLI | `bin/um`, `um/*.py` | `uv run --project` bootstrap; one module per command group, each exposes `register(sub)` |
| Packaging | `pyproject.toml` | `universal-modder 0.2.0`, deps Pillow, numpy, PyYAML, hatchling build |
| Skills | `skills/*/SKILL.md` | copies in `.claude/skills` and `.agents/skills`; `tests/test_um.py::test_skill_copies_match` enforces equality |
| Engine playbooks | `skills/mod-any-game/references/engines/*.md` | `minecraft.md` covers Fabric/NeoForge/Forge routes |
| Knowledge base | `knowledge/games/**`, `knowledge/techniques/**`, `INDEX.md`, `index.json`, `TEMPLATE.md` | validated by `um kb check --index` |
| Agent configs | `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.mcp.json`, `.codex/`, `.cursor/`, `.vscode/`, `opencode.json`, `plugin.json`, `.claude-plugin/` | fal MCP server needs `FAL_KEY` |
| CI | `.github/workflows/test.yml` | pytest, `um --help` for every group, `um kb check --index`, `um publish check .` |
| Examples | `examples/terraria-tmodloader`, `examples/aoe2-de-civ`, `examples/minecraft-gta5-passthrough` | own code only |
| Tests | `tests/test_um.py` (904 lines, 88 tests) | baseline 87 passed / 1 skipped |

**Branches on the fork (VERIFIED via GitHub API).** `main` (= local `claude/dreamy-knuth-rizfww`), ten `ci/pr-*`
branches, four `feat/`/`fix/` KB branches, `skill/port-a-mod`, `integration/thaumcraft-port`, and four
`kb/*thaumcraft*` branches. The designated branch `claude/dreamy-knuth-rizfww` is **not** on the remote (404).

`integration/thaumcraft-port` (head `3e922b1`, 2026-10-11) is described in its own commit as
"main + skill/port-a-mod + the four Thaumcraft knowledge branches + the four um kb fixes". `skill/port-a-mod`
adds a skill for porting an existing mod faithfully and a reference
`references/minecraft-1-7-10-forge-to-1-20-fabric.md`. Both were authored by another agent session
(`session_01KKNUp8RuduYreNsFM6rvZg`) that is still active (last commit 23:28 UTC on 2026-10-11 in `thaumcraft-fabric`).

Relevant knowledge-base notes already on `main` (VERIFIED, paths under `knowledge/games/minecraft/`):

- `kindred-local-llm-ai-companion-fabric.md` — a Fabric **1.20.1** local-LLM companion NPC (Ollama), 19 unit tests,
  17 GameTests. Directly relevant to the Luminary NPC; summarized in `AI_NPC_TECHNICAL_RESEARCH.md`.
- `neoforge-1-21-1-custom-dimension-and-tool-tier.md` — 1.21.1 data-folder facts (singular `recipe/`, `loot_table/`,
  `tags/block/`), GameTests over RCON on a headless server.
- `bloons-td-6-in-minecraft.md`, `ruby-rush-220-talent-forge-1-20-1-...md`, two passthrough notes.

## 2. `thaumcraft-fabric`

**What it is (VERIFIED).** A Fabric port of **Thaumcraft 4.2.3.5 (MC 1.7.10)** to **Minecraft 1.20.1**, Java 17,
Loader 0.16.14, Fabric API 0.92.12+1.20.1, Loom 1.6.12, Gradle 8.8 wrapper, Yarn `1.20.1+build.10`. 101 Java files
in `src/main/java/thaumcraft/**`, 19 `@GameTest` methods in a separate `gametest` source set, one mixin config.
`CLAUDE.md` makes TC 4.2.3.5 the only behavioural authority and forbids TC5/TC6 as sources. `fabric.mod.json` still
says "Thaumcraft 5.2.4 gameplay port" (stale description; the status doc records the switch to TC4 on 2026-10-06).

Remote branches (VERIFIED): `master` (= local designated branch, `e197531`), `claude/epic-pasteur-gzffxb`
(harness/progression work, 2026-10-10), `claude/loving-bohr-pt98hn` ("v0.1.0 release published", 2026-10-11),
`feature/createtaumechanics-and-sborka` (a `createtaumechanics/` add-on built against the port jar and **Create Fabric
6.0.8.1 for 1.20.1**, plus "sborka" = modpack assembly docs; last commit 2026-10-11 23:28 UTC).

The local checkout is at `e197531`, far behind those branches: **do not reason about the port's current code from
the local tree alone.** `THAUMATURGY_RESEARCH.md` covers what the local snapshot shows and what the branch names imply.

## 3. Where Luminary could live (options, see `OPEN_QUESTIONS.md` Q1–Q3)

| Option | Pros | Cons |
|---|---|---|
| A. `universal-modder/luminary/` Gradle project (what the original task implied) | tooling, skills and KB in one clone; the bundled example already shows a Fabric project inside this repo | mixes a toolkit repo with a game project; CI must learn Gradle; the fork tracks upstream `rehan-remade` |
| B. A new dedicated repository | clean history, own CI, own issue tracker | loses nothing: `um` installs from git anywhere |
| C. Inside `thaumcraft-fabric` | thaumaturgy integration is in-process | wrong version (1.20.1), wrong Java (17), and `CLAUDE.md` forbids non-TC4 gameplay there |

Recommendation: B, or A only if the owner wants a single clone. C is not recommended.

## 4. Session toolchain (VERIFIED)

Java 21.0.12.1 (`/usr/lib/jvm/java-21-openjdk-amd64`), Gradle 8.14.3 at `/opt/gradle`, Python 3.13.16, `uv`,
ffmpeg, no Java 25. Outbound HTTPS via a proxy: `maven.fabricmc.net`, `meta.fabricmc.net`, `services.gradle.org`,
`plugins.gradle.org`, `libraries.minecraft.net`, `launchermeta.mojang.com`, `api.modrinth.com`, `repo1.maven.org`
answered; `repo.maven.apache.org` intermittently returned HTTP 429.
