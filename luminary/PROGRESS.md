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

## Status (2026-10-12)
- [x] Baseline import commit
- [x] Backend: accounts, skins, friends, presence, news, Yggdrasil API, admin CLI, deploy files (50 tests)
- [x] Launcher core: profiles, settings, hardened modpack sync, two-step launch, API client, logging (unit tests)
- [x] UI redesign: all six pages functional, 3D skin preview, dialogs, shortcuts (91 launcher tests total)
- [x] GUI-only start: Luminary.exe starter (verified PE GUI subsystem via Mono), CREATE_NO_WINDOW everywhere
- [x] Real-server verification: vanilla + Fabric 1.20.1 with authlib-injector, two protocol clients, and a
      real 1.20.1 client rendering another player's skin
- [x] Docs: docs/DEPLOYMENT.md, docs/SECURITY.md, docs/TESTING.md; CI workflow luminary.yml

## Second pass (2026-10-12)
- Verified the checkpoint: 50 backend and 91 launcher tests passed, the evidence images exist, and the
  servarium `manifest.json` still returns 404. No modpack files exist in the repo or the owner's archive.
- Fixed: zero-byte files in the pack aborted every update (`modpack._download`). Regression test added.
- `make_manifest.py` now validates with the launcher's own rules, refuses names launchers would reject,
  writes atomically, and gained `--check` (local) and `--check-remote` (published pack).
- `launcher.py` startup guard (stdlib only): native message box plus `startup-error.log` instead of a
  silent pythonw exit.
- New `package_release.py`: verified, reproducible Windows distribution with checksums.
- Session-file tests: no plaintext fallback.
- Launcher tests: 119 passed. Backend: 50 passed.
- Parallel work: branch `claude/dreamy-knuth-rizfww` builds a Fabric server mod under `luminary/` (src,
  gradle, docs/decisions). It shares no file with this work except `luminary/README.md`, which both
  branches create. That file was not edited in this pass; expect a merge conflict there.

## Third pass (2026-10-12)
- Verified the checkpoint again: 50 backend and 119 launcher tests pass, the manifest is still 404, and
  the parallel branch is unchanged and has no overlap.
- Fixed account-state defects found by new real-backend tests (`tests/test_account_states.py`):
  - A session revoked elsewhere, or expired while the launcher was open, went unnoticed. The heartbeat
    failure was only logged; it now signs out with a message.
  - An outage at startup never recovered without a restart. It now retries silently every 60 s.
  - Sessions were rotated only at startup. The heartbeat now rotates them near expiry.
  - While offline, the UI offered "Войти" to a signed-in user. It now shows "<name> · нет связи".
- Files: `luminary_app/ui/state.py`, `ui/main_window.py`, `ui/screens/home.py`, plus the new test.
- Launcher tests: 124 passed. Backend: 50 passed.

## Fourth pass (2026-10-12): one-file installer
- `installer/` (NSIS script, `make_art.py`, `build_installer.py`): `Luminary-Setup-<version>.exe` is a
  per-user, themed, Russian installer with shortcuts, an uninstaller and a Play-now checkbox. Its
  shortcuts start `pythonw.exe` directly, so they don't depend on the untested Mono-built starter.
- The launcher sets a Windows AppUserModelID so the taskbar shows Luminary instead of Python.
- `package_release.py` excludes `installer/` from releases.
- Installer checked under Wine (install, pages, uninstall). Not checked on real Windows.
- The installer is unsigned, so SmartScreen will warn. Code signing would be an owner purchase.

## Fifth pass (2026-10-12): launcher auto-update (2.1.0)
- `luminary_app/selfupdate.py` and `ed25519.py` use only the standard library. Releases are signed, and
  launchers check at start and every 6 hours, stage changed files, and install on restart with rollback.
- `publish_launcher.py` (keygen, publish, verify) is the owner's publishing tool. It is excluded from
  player releases.
- `profiles.json` has a `launcher_update` entry pointing at the servarium client-pack `launcher/` folder,
  with the public key of the owner key generated in this session. The key was handed to the owner and is
  not in git.
- The version is now 2.1.0. Installer 2.1.0 built. Launcher tests: 150 passed.
- Owner action: upload the prepared `launcher/` folder (2.1.0) to servarium. Later updates follow
  DEPLOYMENT.md, "Launcher auto-update".

## Owner actions (blocking production use)
1. Rent a VPS + domain for the accounts service; deploy per docs/DEPLOYMENT.md §1.
2. Reserve existing player names from usercache.json (§2).
3. Publish the modpack: `make_manifest.py`, upload, then `--check-remote` (DEPLOYMENT.md, Publishing the modpack).
4. Build Luminary.exe on Windows (`build_starter.bat`), set `accounts.api_url`, run `package_release.py`, ship the zip.
5. Cutover: authlib-injector `-javaagent` + `online-mode=true` + `enforce-secure-profile=false` (§5) -
   needs the hosting panel to accept a JVM argument.
6. Run the Windows manual test procedure (docs/TESTING.md).

## Next steps if work continues
- Optional: launcher self-update (none existed in 1.x; distribution is a manual zip today).
- Optional: lazy page construction if startup time matters (measured 0.61 s vs 0.35 s in 1.x).

## Not verifiable here
- Windows-only behaviour, real PortableMC output, the production server and pack (see docs/TESTING.md).
- GitHub downloads are blocked by this environment's egress policy; building third-party code was refused.
