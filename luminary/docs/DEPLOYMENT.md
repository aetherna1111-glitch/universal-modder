# Deploying Luminary accounts, skins and the server cutover

Nothing here has been deployed. Every step below is an owner action. The order matters, because the
Minecraft server change locks out anyone who has no account yet.

| Step | What | Downtime |
|---|---|---|
| 1 | Run the accounts service on a VPS with HTTPS | none |
| 2 | Reserve existing players' names | none |
| 3 | Release the launcher with accounts enabled (still optional) | none |
| 4 | Players register; existing players claim their names | none |
| 5 | Switch the Minecraft server to `online-mode=true` + authlib-injector | one restart |
| 6 | Make accounts required in the launcher | none |

## 1. Accounts service (backend)

**You need:** a small Linux VPS (1 vCPU / 512 MB is plenty for one server's community) with a public IP,
and a DNS name pointing at it, for example `auth.luminary.example`. This is the only recurring cost. The
game hosting panel that runs the Minecraft server usually cannot run extra web services.

```sh
# as root on the VPS (Debian/Ubuntu)
apt install -y python3-venv caddy
useradd --system --home /opt/luminary-auth --shell /usr/sbin/nologin luminary
mkdir -p /opt/luminary-auth /var/lib/luminary-auth /var/backups/luminary-auth
chown luminary: /var/lib/luminary-auth /var/backups/luminary-auth && chmod 700 /var/lib/luminary-auth
cp -r luminary/backend/* /opt/luminary-auth/            # from this repository
python3 -m venv /opt/luminary-auth/venv
/opt/luminary-auth/venv/bin/pip install /opt/luminary-auth
install -m 600 /opt/luminary-auth/deploy/luminary-auth.env.example /etc/luminary-auth.env
editor /etc/luminary-auth.env                           # set LUMINARY_PUBLIC_URL
cp /opt/luminary-auth/deploy/luminary-auth.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now luminary-auth
cp /opt/luminary-auth/deploy/Caddyfile /etc/caddy/Caddyfile   # replace auth.example.org
systemctl reload caddy
```

Check it:

```sh
curl https://auth.luminary.example/api/v1/health          # {"status":"ok"}
curl https://auth.luminary.example/yggdrasil/             # metadata with signaturePublickey
journalctl -u luminary-auth -f                            # service log (no passwords or tokens)
```

- **Database migrations** run automatically at start (`schema_version` table). Run them by hand with
  `python -m luminary_auth.manage migrate`.
- **The signing key** (`/var/lib/luminary-auth/signing_key.pem`) is created on first start with mode 0600.
  Back it up once, encrypted and off the machine. Losing it is recoverable: start with a new key, then
  restart the Minecraft server. Launchers pick up the new key on their next launch. Leaking it lets someone
  forge skins, but not logins.
- **Backups.** `deploy/backup.sh` makes a consistent SQLite copy plus textures (cron line inside). Restore by
  stopping the service and copying `luminary.sqlite3` and `textures/` back into the data directory.
- **Updates.** Copy the new `backend/` and run `pip install /opt/luminary-auth`, then
  `systemctl restart luminary-auth`.
- **Account recovery** by e-mail is deliberately not implemented, because there is no mail delivery or
  verified address. An admin resets a password with `manage set-password <name>` after verifying the person,
  for example on Discord.

Operator commands (run as the `luminary` user with the env file loaded):

```sh
set -a; . /etc/luminary-auth.env; set +a
/opt/luminary-auth/venv/bin/python -m luminary_auth.manage users
/opt/luminary-auth/venv/bin/python -m luminary_auth.manage news-add "Title" "Text shown in the launcher"
/opt/luminary-auth/venv/bin/python -m luminary_auth.manage disable SomeName
```

## 2. Keep existing players' names (and their inventories)

A Luminary profile's UUID is the offline-mode UUID of its name, which is the same UUID the server already
uses for that player. Registering the exact same name, case included, keeps the world data. To stop
someone else from claiming an existing player's name first:

```sh
# On the Minecraft server: usercache.json lists everyone who has joined.
python3 -c "import json;print('\n'.join(e['name'] for e in json.load(open('usercache.json'))))" > names.txt
# On the VPS:
python -m luminary_auth.manage reserve-names-from names.txt
```

Reserved names cannot be registered from the launcher. When a player proves who they are, an admin either
creates the account for them (`manage create-user Name`, which prompts for a password they then change in
the launcher) or runs `manage unreserve-name Name` so they can register it themselves.

## 3–4. Launcher with accounts (optional at first)

In the launcher folder, edit `profiles.json`:

```json
"accounts": { "api_url": "https://auth.luminary.example", "required": false }
```

While `required` is `false`, the server is still offline-mode. Players can sign in, upload skins and add
friends, or keep playing by nickname. Skins are not visible to others yet, because the offline-mode server
does not send them.

Build the console-free starter once on any Windows PC (it uses the C# compiler included in Windows):

```bat
build_starter.bat        :: creates Luminary.exe next to launcher.py
```

**Package the release.** Prepare a folder with this directory's files plus the binaries that are not in
git: `python\` (portable Python 3.11 with PySide6 6.11 and mcstatus, as before), `jre\` (Java 17+),
`LuminaryLauncher.exe` (PortableMC 5.0.5) and the `Luminary.exe` you just built. Then run:

```bat
python\python.exe package_release.py . ..\release --version 2.0.0 --zip
```

The tool refuses to package if:

- a required file is missing, or `Luminary.exe` is not a GUI-subsystem program
- `profiles.json` or `config_updater.json` is invalid, or the account server is not `https://`
- our own code contains a machine-specific path or key material

It leaves out per-player data (`launcher_data\`, `logs\`, `runtime\`, `.minecraft\`, `nickname.txt`) and
developer files. The output is `release\Luminary\` with `SHA256SUMS.txt` and `release-info.json`, plus
`Luminary-2.0.0.zip`. The zip is byte-for-byte reproducible from the same input, so you can publish its
SHA-256 next to the download. Run `--check` instead of `--version` to only validate.

**One-file installer for players.** From the same prepared folder, on any machine with NSIS 3
(`apt install nsis` or the Windows NSIS package) and Pillow, run:

```sh
python installer/build_installer.py <prepared folder> <output dir> --version 2.0.0
```

This produces `Luminary-Setup-2.0.0.exe`, about 207 MB, and takes about 10 minutes for LZMA compression.

- It installs per user into `%LOCALAPPDATA%\Programs\Luminary`, with no administrator prompt.
- It shows dark kelp-themed welcome and finish pages in Russian. The artwork comes from `assets\` via
  `installer/make_art.py`.
- It creates desktop and Start-menu shortcuts that start the bundled `pythonw.exe` directly, so no console
  appears. It also registers an "Apps & features" entry.
- The finish page has an "Играть сейчас" (Play now) checkbox.
- Upgrades keep `.minecraft`, `launcher_data` and `logs`. The uninstaller removes only installed files and
  asks before deleting game data.
- The installer is not code-signed, so Windows SmartScreen will warn on first run ("Подробнее" →
  "Выполнить в любом случае"). Signing needs a code-signing certificate, which is an owner purchase.

The launcher creates `launcher_data\`, `logs\`, `runtime\` (the authlib-injector jar, downloaded once and
SHA-256 checked) and `.minecraft\` itself. Old `nickname.txt` and `ram.txt` are migrated automatically.
If the launcher cannot start at all (a broken `python\` folder, or an install in a read-only place such as
Program Files), it shows a message box and writes `logs\startup-error.log`. If `logs\` is not writable,
the log goes to `%LOCALAPPDATA%\Luminary\startup-error.log`.

### Publishing the modpack

`config_updater.json` points at the servarium folder. On 2026-10-12 that folder still had no
`manifest.json` (HTTP 404). Until it does, the launcher plays with whatever is in `.minecraft`, as 1.x did,
and shows a notice. The modpack files themselves are not in this repository or in the archive this work
started from, so the manifest can only be built on the machine that holds the real pack.

1. Put the exact client files in `.minecraft\<folder>` for each `sync_folders` entry (`mods`, `config`,
   `defaultconfigs`, `patchouli_books`, `fancymenu_data`), next to `make_manifest.py`.
2. Build the manifest:
   ```bat
   python\python.exe make_manifest.py
   ```
   It refuses to write `manifest.json` when a name would be rejected by launchers. That covers `CON.jar`,
   trailing spaces or dots, `:` or `\` in a name, two files differing only in letter case, symlinks, and
   files over 2 GB. Temp files (`*.part`, `*.tmp`, `Thumbs.db`) are skipped.
3. `python\python.exe make_manifest.py --check` confirms every listed file still matches. It also lists
   files that players would have moved to `.backup`.
4. Upload the files to the servarium folder at the same relative paths, and upload `manifest.json` last.
   Players who start a launch mid-upload then get either the old pack or the complete new one.
5. Run `python\python.exe make_manifest.py --check-remote`. It downloads the published manifest and every
   file and verifies the size and SHA-256 of each.
6. Only then set `"block_launch_on_error": true` if you want launches to stop when the pack server is
   unreachable.

Launchers download into `.minecraft\.luminary_staging\` and verify every file before anything changes.
They swap files in only after all downloads verified. An interrupted update resumes on the next Play and
never leaves a half-updated pack. Files the server no longer lists are moved to
`.minecraft\.backup\<time>\`.

## 5. Minecraft server cutover

Do this in a maintenance window, after most players have accounts.

1. Back up the world, `server.properties` and `usercache.json`.
2. Put `authlib-injector-1.2.8.jar` next to the server jar. Download it from
   `https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar` and check the SHA-256:
   `9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10`.
3. Add the agent to the server's Java command, before `-jar`:
   ```
   java -javaagent:authlib-injector-1.2.8.jar=https://auth.luminary.example/yggdrasil -Xmx... -jar fabric-server-launch.jar nogui
   ```
   On a hosting panel this goes in "JVM arguments" or "startup flags". If the panel does not allow it,
   ask the host's support to add it, because the cutover cannot work without it.
4. Edit `server.properties`:
   ```
   online-mode=true
   enforce-secure-profile=false
   ```
   The second setting is needed because chat signing keys are not issued; see SECURITY.md.
5. Restart. The log must show `[authlib-injector] [INFO] Authentication server: https://auth...` and
   `Disabled Mojang namespace`.
6. Verify from any PC with Python and `cryptography`, using two test accounts, one with a skin:
   ```sh
   python luminary/backend/tools/verify_skins.py --api https://auth.luminary.example \
       --server 135.106.221.145:27195 --a Alice:password --b Bob:password
   ```
   All lines must say `PASS`.

**Effect.** Only players with a Luminary account can join, and nobody can join under someone else's name.
Players are admitted with the same UUIDs as before, so inventories are kept. Other launchers can still join
if they support authlib-injector (HMCL, Prism Launcher with an authlib-injector account) and sign in with
Luminary credentials. Offline launchers such as TLauncher are refused.

**Rollback.** Remove the `-javaagent` flag, set `online-mode=false` and restart.

If the server runs an offline-mode login mod (e.g. a `/login` password mod), it becomes redundant after the
cutover. The server configuration was not available for inspection, so check for one.

## 6. Require accounts

Set `"required": true` in `profiles.json` and ship the launcher. Nickname play then disappears from Home.
