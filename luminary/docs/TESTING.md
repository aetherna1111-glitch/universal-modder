# Testing and verification

Recorded on 2026-10-12 (second pass the same day) in a Linux container (Python 3.11, PySide6 6.11.2 headless, OpenJDK 21). There was
no Windows machine and no access to the production server.

## Automated tests

```sh
cd luminary/backend  && pytest -q                                   # 50 passed
cd luminary/launcher && QT_QPA_PLATFORM=offscreen pytest -q tests   # 150 passed
cd <repo root>       && uv run --with pytest pytest -q tests        # toolkit: 87 passed, 1 skipped (unchanged)
./bin/um publish check . && ./bin/um kb check --index                # 0 failures (2 old warnings); 157 notes pass
```

`.github/workflows/luminary.yml` runs the first two on every change under `luminary/`.

| Area | Covered by |
|---|---|
| Navigation and screen state | every sidebar item opens its real page, plus shortcuts (`test_ui.py`) |
| Settings | validation, atomic save, corrupt-file fallback, 1.x migration, UI rejects invalid JVM args and Java paths |
| Launch pipeline | fake PortableMC (documented machine output) and fake Java as real subprocesses. Covers argument shapes, `--dry`, marker insertion, Windows backslashes, error mapping, cancel, exit codes, crash-report detection, log capture and redaction, offline UUIDs. Full offline and account launches through the UI. |
| Modpack sync | path confinement (13 attack paths), SHA-256 mismatch never reaches the pack, interrupted download leaves pack untouched and resumes with HTTP Range, truncated transfer retried, backup of removed files, hash cache |
| Accounts | register/login/logout/refresh/expiry, Argon2id hashes, hashed tokens, identical errors for unknown user and wrong password, per-account lockout across IPs, per-IP limit, password change revokes other sessions, disabled users, SQL-injection strings |
| Authorization | game tokens cannot manage accounts, session tokens cannot join, nobody else can answer a friend request, presence is friends-only, a join for someone else's profile is refused |
| Skins | modern/legacy/slim, wrong sizes, GIF/PHP disguised as PNG, bad CRC, truncation, oversize, huge-dimension header refused before decode, metadata stripped, replace/delete/garbage-collect, path traversal on `/textures` |
| 3D preview | face orientation of all six sides, top-left texel placement, slim arms narrower, legacy legs mirrored, drag/zoom/reset, local PNG validation |
| API client | the launcher client against the real backend under uvicorn, offline/unreachable server, foreign texture hosts refused |
| Modpack publishing (`make_manifest.py`) | a generated manifest is accepted by the launcher's own parser and syncs a fresh install end to end. Names launchers would reject are refused (`CON.jar`, trailing space, `:`, case-only duplicates, symlinks), and temp files are skipped. The previous manifest survives a refused build. `--check` finds changed, missing and unlisted files. `--check-remote` finds corrupted and missing published files and an unpublished manifest. |
| Zero-byte files in the pack | regression test: an empty file in the pack used to make every update fail. Fixed in `modpack._download`. |
| Startup guard (`launcher.py`) | a missing PySide6 (also in a real subprocess), a read-only folder, and an unwritable `logs\` (falls back to `%LOCALAPPDATA%`) each give a message plus `startup-error.log`. Normal exit codes pass through. |
| Account-state transitions (`test_account_states.py`, real backend) | a session expired at startup signs out with a message. A session revoked on another PC ("sign out everywhere") is noticed by the heartbeat and signs out. An outage at startup keeps the session, allows nickname play, shows "<name> · нет связи" instead of "Войти", and retries silently. Recovery when the service returns needs no restart and no UI flicker. Losing the network while signed in switches to offline. A session close to expiry is rotated while the launcher stays open. |
| Session file | it holds only protector output, a protector failure writes nothing (no plaintext fallback), and an unreadable file (another user's DPAPI blob) signs out and is deleted. DPAPI itself is Windows-only. |
| Release packaging (`package_release.py`) | builds from the real sources plus stand-in binaries, with the real starter compiled by Mono. It ships no user data or dev files, `SHA256SUMS` match, and the zip is identical across two builds. It refuses a console starter, a missing Java or PySide6, localhost or plain-http account servers, machine paths and key material. |
| Installer (`installer/`, `test_installer.py`) | artwork sizes, a per-user install, and an uninstaller that never wipes the install folder or touches player data on upgrade. Shortcuts point at `pythonw.exe`. A real NSIS build of a small installer is a GUI-subsystem exe. |
| Self-update (`test_selfupdate.py`, `test_ed25519.py`) | RFC 8032 vectors and interop with `cryptography`. The real `publish_launcher.py` publishes to a local HTTP host; a real launcher folder checks, stages only changed files, and installs through `launcher.py` in a separate process. That covers the version bump, a new module, deleting a removed module, and keeping player data. Refused cases: a forged or tampered signature, a foreign key, downgrades, paths outside the launcher (`python/`, `jre/`, `..`, `.minecraft`, `launcher_data`) and a corrupted file on the host. A broken release rolls back and is not offered again. A failed install restores everything and retries 3 times. `publish` refuses a key that players would reject. The UI service stages an update and Settings shows it. |
| GUI-only start | starter compiles with Mono to PE subsystem 2 (GUI), argument quoting round-trips under CommandLineToArgvW rules, static guard that every subprocess call hides its console, no `print()` diagnostics |

## Verified against real software

- **Real Minecraft servers.** Ran the official 1.20.1 server jar (SHA-1 checked against Mojang's
  manifest) and a Fabric 1.20.1 server (loader 0.19.5), each with authlib-injector 1.2.8 pointed at the
  backend and `online-mode=true`. `backend/tools/verify_skins.py` logged in two accounts with the real
  protocol, and every check passed on both servers:
  - both players admitted only after a verified join
  - the account UUID is used, equal to the offline UUID, so player data is kept
  - the second client receives the first player's signed textures, the signature verifies, and the PNG downloads
  - a foreign token and a missing session are both refused with "Failed to verify username"
- **A real 1.20.1 client.** The official client, with authlib-injector and a game token from the backend,
  joined that server as "Diver". It downloaded "Shipovnik"'s skin into its skin cache: the file is
  byte-identical to the uploaded texture, under the cache name Minecraft derives from the texture hash.
  It then rendered the skin on the other player (`images/ingame-skin-other-player.jpg`). Software OpenGL
  on Xvfb was used.
- **The launcher UI** was screenshotted on every page, signed out and signed in, against the live backend
  (`images/launcher-*.jpg`). The account-mode test passes the token the game received to the backend's
  join endpoint, and `hasJoined` returns the signed profile.

**Installer under Wine (Linux, Wine 9, not real Windows).** A small build of the same installer script was
installed silently and interactively. Results:

- Files landed in `%LOCALAPPDATA%\Programs\Luminary`, with desktop and Start-menu shortcuts and the
  uninstall registry entry.
- The welcome, folder and finish pages rendered in Russian with the dark theme.
- A silent uninstall removed program files, shortcuts and the registry key, and kept `.minecraft` and
  `launcher_data`.
- A Cyrillic-named shortcut appears only with a UTF-8 locale, which is a Wine/container quirk.

The full 207 MB installer was built but not run under Wine, because its payload is real Windows Python and
Java.

## Not verified here

| Item | Why | How to verify |
|---|---|---|
| Windows behaviour: no console on Play, DPAPI session file, `Luminary.exe` at runtime, frameless resize, sounds | no Windows machine | Steps 1–4 below |
| `Luminary.exe` built by Windows' own csc, and `package_release.py` on the real `python\`/`jre\` folders | no Windows machine and no owner binaries here | DEPLOYMENT.md §3–4, then Step 1 below |
| Real PortableMC 5.0.5 `--dry` output | building third-party code was refused here, and GitHub downloads are blocked | Step 3; `logs/install-*.log` shows the raw output |
| The production server and the servarium modpack | no access; the pack has no `manifest.json` yet | DEPLOYMENT.md §5 and `verify_skins.py` |
| Fabric mods from the real pack on the client | the pack is not published | play once after publishing the pack |
| Live server status in the UI | this sandbox blocks raw TCP to the game server, so it shows "Недоступен" here | open the launcher on a normal network |

## Manual test procedure (Windows, two PCs or two Windows accounts)

1. Copy the distribution folder, run `build_starter.bat`, and double-click `Luminary.exe`. No console window
   may appear. `logs\launcher.log` starts with `Luminary Launcher 2.0.0 starting`.
2. Click every sidebar item. Settings → change memory, restart, and check it was kept. Settings → Java
   "Проверить" reports Java 17+.
3. With `accounts.api_url` empty, enter a nickname and click Play. Watch for:
   - progress through "Проверка сборки" and "Установка" with no console window
   - Minecraft starting and joining the server
   - `logs\install-*.log` ending with `[exit code 0]`, and `logs\game-*.log` filling up
   - closing the game restores the launcher
4. Force a crash, for example with an invalid JVM argument such as `-XX:+NoSuchOption` in Settings. An
   "Игра вылетела" or "Не удалось запустить" dialog must appear, with working log links.
5. With the backend deployed and the server cut over, test with two accounts A and B. Upload a skin as A
   and preview it in 3D. Save it, and try the classic/slim switch. Both players launch and meet in game, and
   B must see A's skin. Then run `tools/verify_skins.py` against production.
6. Sign out, and check that `launcher_data\session.bin` is gone. Copy a `session.bin` to another Windows
   user: the launcher must treat it as signed out, which shows DPAPI binds it to the user.

## Performance (measured, this container, files in OS cache)

| Measurement | 1.x | 2.x |
|---|---|---|
| Modpack check on Play (200 files, 300 MB) | 267 ms, on the GUI thread, every launch | 281 ms first run, 4 ms after (hash cache), on a worker thread |
| Time to first painted window (incl. Qt import) | ~350 ms | ~610 ms (six real pages instead of two) |

On a typical Windows hard disk the 1.x check reads the whole pack on every Play and freezes the window while
it does. The 2.x check reads it once.
