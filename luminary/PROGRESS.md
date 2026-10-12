# Luminary launcher: progress checkpoint

Single source of truth for resuming this work. Keep it short; update at milestones.

## Findings (audit, confirmed in code)
- Launcher: PySide6 app (`launcher/launcher.py`), run by bundled Windows Python 3.11 via `run.bat`
  (`pythonw.exe`). Bundled site-packages: PySide6 6.11.2, mcstatus 14.2.0, Pillow 12.3 (+ unused
  customtkinter/pygame).
- `LuminaryLauncher.exe` is PortableMC 5.0.5 (Rust CLI, console subsystem). Launch = `--main-dir .minecraft
  start fabric:1.20.1 --username N --uuid U --join-server H --join-server-port P --jvm jre/bin/javaw.exe`.
- Sidebar: Settings/Mods/Friends registered with index -1, never connected -> dead buttons.
- Offline UUID computed with Python `uuid3(NAMESPACE_DNS, ...)` - differs from the server's Java
  `nameUUIDFromBytes` UUID.
- Console window on Play: console-subsystem PortableMC spawned from windowless pythonw without
  CREATE_NO_WINDOW. `run.bat` flashes a cmd window. `print()` output is lost under pythonw.
- Updater: no sha256 check after download, manifest paths not confined (`../` escapes), hashing and
  manifest fetch on the GUI thread, partial updates on interruption.
- Modpack host (servarium) folder exists but has no `manifest.json` yet (404).
- PortableMC offline mode passes an empty access token and has no option to set one; `--dry --output
  machine` prints the full JVM command (`jvm_args` line, tab-separated, only \n and \t escaped).
- No backend existed.

## Decisions
- Accounts: FastAPI + SQLite service (`backend/`), Argon2id, hashed opaque tokens, rate limits in SQLite.
- Identity + skins in game: authlib-injector 1.2.8 (pinned sha256) on client and server, backend
  implements its Yggdrasil API. Server must switch to `online-mode=true` + `-javaagent` (owner action).
  Profile UUID = offline UUID of the name, so existing player data is kept.
- Launch: PortableMC `--dry` installs/validates, launcher spawns Java itself (token, javaagent, -Xmx,
  CREATE_NO_WINDOW, logs to files).
- 3D preview: QPainter affine-textured box renderer, no new client dependency.

## Status
- [x] Baseline import commit, backend + 47 tests passing
- [ ] Launcher core (settings, modpack sync, launch pipeline, API client, logging)
- [ ] UI redesign + all screens
- [ ] Skin 3D preview
- [ ] GUI stub exe / console fixes verified
- [ ] Docs (deploy, server cutover), CI workflow

## Blocked / not verifiable here
- GitHub downloads are blocked by this environment's egress policy; building third-party code was refused
  by the session's safety policy (PortableMC not run here).
- No Windows, no GPU Minecraft client, no access to the real server.
