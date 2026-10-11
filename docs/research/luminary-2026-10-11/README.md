# Luminary research set (2026-10-11)

Research and architecture notes prepared for **Luminary**, a Minecraft project (civilization progression, shared
knowledge, thaumaturgy, cosmic mystery, an autonomous AI-driven NPC) that the repository owner intends to build on
top of this repository's tooling.

These files are **additive research only**. They were written under an explicit add-only mandate: no existing file in
any repository was modified, no build or test was run after the mandate took effect, and nothing here was committed.

## Why this directory and not `knowledge/`

`um kb check --index` (see `um/kb.py`, `notes()`) walks **every** `*.md` under `knowledge/` except `README.md`,
`INDEX.md`, `TEMPLATE.md` and `engines/`, validates YAML front matter against `knowledge/TEMPLATE.md`, and fails CI
when `knowledge/INDEX.md` is stale. The index is a protected file that cannot be regenerated under the add-only
mandate, so any new file under `knowledge/` would turn CI red (`.github/workflows/test.yml`, step "knowledge base";
also `tests/test_um.py::test_repo_knowledge_is_valid`). `docs/` previously held only `docs/media/`, so a new
`docs/research/` tree is safe for CI and clearly separated from the agent-written field notes.

Once the owner wants parts of this material in the knowledge base proper, the right vehicle is one or more
`kind: technique` notes created with `um kb new --kind technique ...` followed by `um kb index`.

## Evidence labels

Every non-trivial claim carries one of:

- **VERIFIED** — read from repository files, from a tool's output in this session, or from an official source fetched
  in this session (URL and date in `SOURCES.md`).
- **LIKELY** — consistent with verified evidence and standard practice for the stated versions, but not confirmed
  against the exact version in this session.
- **UNVERIFIED** — assumption, recollection, or recommendation that needs a check before it is relied on.

## Files

| File | Content |
|---|---|
| `PROJECT_MAP.md` | Both repositories, branches, toolchain, what is where |
| `EXISTING_ARCHITECTURE.md` | How `um`, the skills, the knowledge base, the bundled Fabric example and the Thaumcraft port are built |
| `MINECRAFT_FABRIC_1_21_1.md` | Verified Fabric/Yarn 1.21.1 signatures and the toolchain matrix (Loom, Gradle, JVM) |
| `DEPENDENCIES_AND_COMPATIBILITY.md` | Version matrix, the 1.20.1 vs 1.21.1 split, library availability and licenses |
| `THIRD_PARTY_MOD_APIS.md` | GeckoLib, Create, Modonomicon, Patchouli, Polymer, permissions API, FTB Quests |
| `THAUMATURGY_RESEARCH.md` | The Thaumcraft 4 port in `thaumcraft-fabric`: state, integration points, version conflict |
| `LUMINARY_SYSTEM_DESIGN.md` | Proposed module boundaries, data model, interfaces, persistence, story control |
| `AI_NPC_TECHNICAL_RESEARCH.md` | Server-authoritative NPC architecture, memory, LLM provider abstraction, Ollama facts |
| `SECURITY_AND_PERFORMANCE.md` | Threat model for the LLM boundary, thread safety, budgets, secrets handling |
| `TESTING_AND_VALIDATION.md` | Baseline results recorded in this session and the proposed test pyramid |
| `OPEN_QUESTIONS.md` | Decisions only the owner can make, ranked by impact |
| `SOURCES.md` | Every URL and file consulted, with fetch dates |
| `SESSION_NOTES.md` | What this session did before the mandate changed, including stray untracked files to clean up |
