# Security model

## Identity: before and after the server cutover

**Today (`online-mode=false`).** The server believes whatever name a client sends. Anyone can join as
anyone, and Luminary accounts cannot change that, because the server never asks. Skins uploaded to
Luminary are not shown to other players either, since an offline-mode server sends no textures.

**After the cutover (`online-mode=true` + authlib-injector on the server).** This is the same
challenge–response Minecraft uses with Mojang, with the accounts service as the authority:

1. The server sends the client a random server ID and its public key. The client derives a hash from them
   and a secret only it and the server know.
2. The client posts that hash with its game token to `/yggdrasil/sessionserver/session/minecraft/join`.
   The service accepts only a valid, unexpired game token whose profile matches the claimed UUID.
3. The server asks `/hasJoined?username=…&serverId=…`. The service answers only for a join made within the
   last 30 s for that exact name and hash, and only once. It returns the profile with a signed skin.

So a forged name or UUID is refused, and so is another player's token. This was tested against real vanilla
and Fabric 1.20.1 servers with `tools/verify_skins.py`.

**What this is not.** It is not Microsoft-account-level identity:

- Accounts are password-only, with no 2FA, no e-mail and no self-service recovery.
- Whoever runs the accounts service, or compromises it, can act as any player.
- The game token is visible to every mod in the game process. Vanilla's Microsoft token is too.

## Accounts and sessions

| Item | Policy |
|---|---|
| Passwords | Argon2id (argon2-cffi defaults: 64 MiB, t=3, p=4), rehashed if parameters change. 8–128 chars, not the username, not on a small common-password list. The launcher never stores passwords. |
| Launcher session | 256-bit random token. Only its SHA-256 is stored server-side. 30 days, rotated by the launcher when under 7 days remain. Sent as `Authorization: Bearer`. |
| Game token | Separate 256-bit token, 48 h, issued per launch from a session. It can only do Yggdrasil operations (join/validate/refresh), never account changes. Revoked with its parent session. |
| Revocation | Logout revokes the session and its game tokens. "Sign out everywhere", a password change (all other sessions) and disabling an account revoke everything. An open launcher notices a revoked or expired session at its next heartbeat (within about 60 s) and signs out. If the service is unreachable it keeps the session and retries every 60 s. |
| Client storage | `launcher_data/session.bin`, encrypted with Windows DPAPI for the current user; owner-only file elsewhere. |
| Brute force | Per IP: 20 login attempts/10 min, 100/day. Per account: 10 failures/15 min from any IP, which slows credential stuffing. Registration: 5/hour per IP. Limits live in SQLite and survive restarts. |
| Enumeration | Login errors are identical for "no such user" and "wrong password", and both cost an Argon2 verification. Registration has to say "name taken", since names are public in-game anyway. User search requires a session. |
| Authorization | Every write acts on the token's own account. Friend requests can only be answered by their addressee, and other request IDs answer "not found". Presence is visible to friends only. |
| SQL | Parameterised queries only. A test feeds injection strings to login and search. |

## Skins

- Uploads are limited to 128 KiB, by Content-Length and by streaming count. Caddy also caps bodies at 256 KiB.
- The PNG signature, the IHDR chunk and its CRC, the colour type and bit depth are checked before decoding.
  Only 64×64 and 64×32 pass, so a "huge image" header is rejected without decoding. Pillow then decodes,
  converts to RGBA and re-encodes without metadata. Fully transparent skins are refused.
- Files are content-addressed (`textures/<sha256>.png`). Unreferenced textures are deleted, and the path is
  built from a validated 64-hex hash only.
- Textures are public, which is required: Minecraft clients download them without credentials, as with
  Mojang. Only the owner's session can change or delete a skin.
- The skin is signed (SHA1withRSA, 4096-bit key), as authlib requires. Clients accept it because
  authlib-injector installs the service's public key and whitelists its domain.

## Transport and logs

- HTTPS is terminated by Caddy with automatic certificates. The launcher refuses non-HTTPS account URLs
  except `localhost`, never disables certificate checks, and does not follow redirects, so a token can't
  leak to another host.
- The launcher log (`logs/launcher.log`) passes through a redaction filter for token/password-like
  values. Command lines are logged with `--accessToken ***`. A test asserts that no token or password
  reaches any log file.
- The service never logs request bodies. Validation errors list field names only.

## Launcher integrity

- Modpack manifest paths are confined to the configured sync folders. Absolute paths, drive letters,
  `..`, `//`, `./`, Windows reserved names and duplicates are refused.
- Every downloaded file must match its manifest size and SHA-256 before anything in `.minecraft` changes.
  An interrupted or failed update leaves the pack as it was.
- authlib-injector is pinned by URL and SHA-256 and re-checked before each use. The launcher executes no
  other downloaded code. PortableMC and Java are the binaries shipped in the launcher folder.
- Child processes are only ever stopped through their own process handle, never by name.

## Known limits

- Chat signing is off (`enforce-secure-profile=false`, `feature.enable_profile_key=false`), as with any
  non-Mojang authentication. Chat reports to Mojang are unavailable.
- There is no CAPTCHA, only per-IP limits on registration. IPv6 users can rotate addresses. Close
  registration (`LUMINARY_REGISTRATION_OPEN=0`) if abuse appears.
- Rate-limit state is per accounts database. That is fine for the single-instance deployment described here.
