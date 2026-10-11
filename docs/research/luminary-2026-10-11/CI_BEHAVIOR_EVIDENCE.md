# CI behaviour of `universal-modder`, from its implementation (VERIFIED 2026-10-11)

## Pipeline (`.github/workflows/test.yml`)

On `push`, `pull_request`, `workflow_dispatch`, ubuntu-latest, `astral-sh/setup-uv@v6`, `apt-get install ffmpeg`:

1. `uv run --with pytest pytest -q tests`
2. `./bin/um --help` and `./bin/um <group> --help` for `scan fal comfy sprite render3d video win backup publish kb`
3. `./bin/um kb check --index`
4. `./bin/um publish check .`

No Gradle, no Java step. A Fabric project added to this repository would **not** be built by CI unless a new workflow
is added (a change to protected CI files — not done).

## What each step checks (from source)

**pytest** (`tests/test_um.py`, 88 tests; 87 pass + 1 skip locally):
- `test_repo_knowledge_is_valid`: iterates `kb.notes(REPO/"knowledge")`, asserts `check_note` has no failures for each,
  and asserts `knowledge/INDEX.md` equals `kb.build_index(root)` output exactly. **Any new `*.md` under `knowledge/`**
  (outside `README.md`, `INDEX.md`, `TEMPLATE.md`, `engines/`) is validated as a note and changes the index →
  the test fails until `um kb index` rewrites `INDEX.md`/`index.json`.
- `test_skill_copies_match`: `.agents/skills` and `.claude/skills` must be byte-identical copies of `skills/` and not
  symlinks; `.gemini/skills` and `.github/skills` must not exist.
- `test_path_hook_writes_a_posix_root`: skipped unless `cygpath` exists (the 1 skip).

**`um kb check --index`** (`um/kb.py`): same enumeration as above; prints `FAIL knowledge/INDEX.md is out of date`
when stale; exit 1 on any failure. `check_note` fails on missing front matter, schema violations, secrets, decompiler
dumps and code blocks over 150 lines; warns over 60.

**`um publish check .`** (`um/publish.py` `check`): walks every file except `.git .venv venv node_modules
__pycache__ .pytest_cache obj .vs .idea`.
- **FAIL** on: any `.env`/`*.env` file; secret patterns in text files (fal key, `FAL_KEY=` assignment, `sk-ant-…`,
  OpenAI `sk-…`, GitHub `ghp_…`/`github_pat_…`, `AKIA…`, PEM private key headers).
- **WARN** (non-fatal) on: engine archives > 5 MB; decompiler auto-names in code files; **absolute user paths**
  (`C:\Users\…`, `/home/<user>/…`, `/Users/<Name>/`) in text files; missing README; fal manifest without credit.
- Exit code 1 only on FAIL.

## Effect of this research directory on CI (assessment)

- `docs/research/luminary-2026-10-11/*.md` is outside `knowledge/` → not seen by `kb check` or
  `test_repo_knowledge_is_valid`. **No failure.**
- `publish check .` will emit **WARN "absolute user path"** for files here that mention `/home/user/...`
  (`PROJECT_MAP.md`, `SESSION_NOTES.md`, `SOURCES.md`, this file). Warnings do not fail CI. No secret patterns are
  present (the documents name env-var *names*, not values).
- The stray untracked `luminary/` scaffold: its `.gradle/` and `build/` caches contain binary `.bin`/`.lock` files
  (not text, not archives > 5 MB) and `gradle.properties`/`build.gradle` (not in `TEXT_EXT` list except `.properties`?
  — `.properties` is not listed in the visible part of `TEXT_EXT`; UNVERIFIED) → LIKELY no CI effect, but it should not
  be committed regardless.

## Local hooks (VERIFIED)

`hooks/hooks.json` (plugin) and `.claude/settings.json` (clone) run `hooks/add-to-path.sh` on `SessionStart` to put
`bin/` on PATH. No pre-commit hooks in the repository. The `stop-hook-git-check.sh` that asked this session to
commit untracked files is a **user-level** Claude Code hook (`~/.claude/`), not repository CI.
