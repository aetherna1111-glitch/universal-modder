# MODLOG: Far Cry (2004) co-op

Journal for the FarCryCoop mod. Facts are tagged **[verified: source]** when read from Crytek's
CryENGINE MOD SDK 1.4 code/docs, and **[unverified]** when they still need the running game.

## Environment (this session)
- Linux cloud container. **No Far Cry install, no Windows machine, no GPU.** Nothing here has run inside
  the real game. Wine 9.0 is available for smoke tests of the Windows binaries.
- Toolchain: clang-cl 18 + lld-link (MSVC ABI, i686), MSVC CRT + Windows SDK via `xwin --arch x86 splat`,
  mingw-w64 (i686) for the launcher.
- SDK source: `github.com/fholger/farcry_vrmod` commit `ef7b86c` = CryENGINE MOD SDK 1.4 + VS2022/STLport
  compile fixes. The VR mod proves a modern-MSVC CryGame.dll runs against the retail 1.4 engine.

## Verified engine facts
1. Mods live in `Mods/<Name>/`, which overrides the game root; launch with `-MOD:<Name>`;
   `ModDesc.txt` / `ModExe.lua` / `ModPreview.dds` in the mod root. **[verified: FCMG.pdf]**
2. With patch 1.4 the engine loads `Mods/<Name>/Bin32/CryGame.dll` instead of the stock one; players must be
   on 1.4. **[verified: GameSource-Readme.pdf]**
3. CryGame routes malloc/new through `CrySystem.dll!CryMalloc/CryFree` (`CryMemoryManager.h`,
   `USE_NEWPOOL`), so a static CRT in our DLL does not mix heaps with the engine. **[verified: source]**
4. Single player already runs as a local server + local client over `CXServer`/`CXServerSlot`.
   `IsMultiplayer()` = not (server and client) or listen server. **[verified: Game.cpp]**
5. Server picks rules from `Scripts\<g_GameType>\GameRules.lua`, falls back to `Scripts\Default`.
   Client runs `scripts/<g_GameType>/ClientStuff.lua`, takes `g_GameType` from the server context and
   rejects a server running another mod ("Wrong Mod"). **[verified: XServerRules.cpp, XClient.cpp]**
6. `CXSystemBase::LoadLevelCommon` initialises the AI system, triangulation and all level entities on
   **both** server and client; level entities keep their XML entity ids and spawn with
   `netPresence=false`. **[verified: XSystemBase.cpp]**
7. Every entity is in each slot's snapshot list; updates (`XSERVERMSG_UPDATEENTITY`, unreliable,
   distance-prioritised, bandwidth-budgeted) only go out for `GetNetPresence()==true`. Entity **state**
   changes (`XSERVERMSG_SETENTITYSTATE`, reliable) go out for any entity whose state is not client-side.
   **[verified: XSnapshot.cpp, NetEntityInfo.cpp]**
8. `CPlayer::Write/Read` already serialise stance, weapon, fire mode, firing, reload, lean, grenade fire for
   non-owned actors; health/ammo only to the owning client. **[verified: XPlayer.cpp]**
9. `CXGame::Update` passes `ESYSUPDATE_MULTIPLAYER` to `ISystem::Update` in MP. What CrySystem.dll skips
   with that flag (AI?) is **closed source [unverified]**. `IAISystem::GetAITickCount()` lets the DLL detect
   it at run time.
10. `CXGame::Save` is a no-op in MP. `Game:TouchCheckPoint` dereferences `GetMyPlayer()` without a NULL
    check (would crash a dedicated server). **[verified: GameLoading.cpp, ScriptObjectGame.cpp]**
11. Players register with AI from Lua (`AI:RegisterWithAI(id, AIOBJECT_PLAYER, props)` reads `eye_height`,
    `groupid`, `species`); there is no C++ getter for agent parameters. **[verified: ScriptObjectAI.cpp]**
12. MP players with no `equipEquipment` get `MainPlayerEquipPack` (the mission's pack).
    **[verified: BasicPlayer.lua from the VR mod]**
13. Host = `g_GameType` + `Game:LoadLevelListen(map, mission)`; join = `Game:Connect(ip)`.
    **[verified: ScriptObjectGame.cpp]**
14. Lua dialect is 5.0 style (`for k,v in t do`, `getn`, `sprintf`). **[verified: shipped scripts]**

## Route
Native: ship a rebuilt `CryGame.dll` (SDK 1.4 source + small co-op hooks) in `Mods/FarCryCoop/Bin32`, plus a
`Coop` game type (Lua) and a Windows launcher. Reason: the engine already has authoritative server,
reliable/unreliable channels, snapshots and interest management; co-op is a set of single-player assumptions
to lift, not a new netcode. The original game files are never touched.

## Build log
- Stock SDK CryGame.dll builds with clang-cl after 11 compile-only fixes (`crygame/toolchain_fixes.py`):
  friend injection, `template<>` specialisations, MASM `$labels`, a nested forward declaration.
  Exports `CreateGameInstance`, `CryModuleGetMemoryInfo`; imports only system DLLs (static CRT).
- Co-op DLL = stock + `crygame/coop/*` + 4 patches (Game.cpp, XSystemServer.cpp, ScriptObjectGame.cpp/.h).
  Built first time; `Coop.cpp` is clean at /W4 apart from include-case notes.
- Wine 9 load test (stub CrySystem.dll exporting CryMalloc & co.): both DLLs load, static init runs,
  `CreateGameInstance()` allocates through CryMalloc (8 allocations).
- `Scripts/Coop/GameRules.lua` runs under real Lua 5.0.3 against mocked engine objects (45 checks). Lua 5.0.3
  has no `getglobal`/`mod` globals; Far Cry's build does (shipped scripts use them). The rules avoid `mod`.
- `BasicPlayer:IsAlive()` returns a boolean (`GetState()=="Alive"`), not 1. `_time` is not a global the
  shipped scripts use; `System:GetCurrAsyncTime()` is.
- Launcher (mingw, static) tested under wine against a fake install: detection, version gate, level list,
  install/backup, dry-run and real CreateProcess launch, LAN discovery between two launcher processes.
- Damage path: `CXServerRules::OnHitPlayer/OnHitObject` are never called in this DLL; damage goes through
  entity scripts to `GameRules:OnDamage(hit)` (also what FCMG.pdf edits). Friendly fire wraps `OnDamage`.
- `Game:LoadLevel` forces `g_GameType=Default` and a non-listen server: campaign level transitions end a
  co-op session (known issue).

- 0.2.0: `Game:LoadLevel` during a co-op session now changes level on the server like MP map rotation
  (`LoadLevelCS(true, map, mission, true)`, what `Game:LoadLevelMPServer` calls); a pure client ignores it.
  Stock behaviour is unchanged outside co-op. Unverified in game.
- 0.2.0: FarCryCoop.exe embeds the mod files (RCDATA + manifest with per-file SHA-256) and extracts them to
  `%TEMP%\FarCryCoop-<hash>` when no `mod\FarCryCoop` folder sits next to it. Tested under wine: the
  installed files are byte-identical to the build.

## Next
- The in-game test plan in README.md, on one campaign level, by someone with the game.
