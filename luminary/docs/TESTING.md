# Testing and verification

Recorded on 2026-10-12 in a Linux container (Python 3.11, PySide6 6.11.2 headless, OpenJDK 21). There was
no Windows machine and no access to the production server.

## Automated tests

```sh
cd luminary/backend  && pytest -q                                   # 50 passed
cd luminary/launcher && QT_QPA_PLATFORM=offscreen pytest -q tests   # 91 passed
cd <repo root>       && uv run --with pytest pytest -q tests        # toolkit: 87 passed, 1 skipped (unchanged)
./bin/um publish check . && ./bin/um kb check --index                # 0 failures; 157 notes pass
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

## Not verified here

| Item | Why | How to verify |
|---|---|---|
| Windows behaviour: no console on Play, DPAPI session file, `Luminary.exe` at runtime, frameless resize, sounds | no Windows machine | Steps 1–4 below |
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
