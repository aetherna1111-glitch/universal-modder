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

What to ship in the launcher zip:

```
Luminary.exe  launcher.py  luminary_app\  profiles.json  config_updater.json  make_manifest.py
assets\  sounds\  luminary.ico  run.bat  run-debug.bat  LuminaryLauncher.exe (PortableMC 5.0.5)
python\  (portable Python 3.11 with PySide6 6.11 and mcstatus, as before)   jre\  (Java 17+)
```

The launcher creates `launcher_data\`, `logs\`, `runtime\` (the authlib-injector jar, downloaded once and
SHA-256 checked) and `.minecraft\` itself. Old `nickname.txt` and `ram.txt` are migrated automatically.

**Modpack.** The servarium folder in `config_updater.json` has no `manifest.json` yet (HTTP 404), so the
launcher currently plays with whatever is in `.minecraft`, as 1.x did. Publish the pack with the existing
`make_manifest.py`, then upload `manifest.json` and the files. Set `"block_launch_on_error": true` only
once the manifest is online.

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
