---
kind: game
title: "Co-op for Far Cry (2004) from the CryENGINE MOD SDK 1.4 CryGame.dll (built on Linux, untested in game)"
game: Far Cry
games_also: []
game_version: "Far Cry patch 1.4 (target; no install was available to test)"
platform: windows
engine: cryengine
route: native-hook
tools: ["CryENGINE MOD SDK 1.4 (via fholger/farcry_vrmod@ef7b86c)", "clang-cl 18 + lld-link", "xwin", "mingw-w64", "Wine 9", "Lua 5.0.3"]
anti_cheat: "none in the offline/LAN game; official online servers were shut down in 2016"
status: in-progress
agents: ["Claude Code"]
humans: []
date: 2026-10-24
links: ["https://github.com/rehan-remade/universal-modder/tree/main/examples/farcry-coop", "https://github.com/fholger/farcry_vrmod"]
tags: [co-op, multiplayer, cryengine, game-dll, sdk, ai, netcode, cross-compile, clang-cl, wine]
---

# Co-op for Far Cry (2004) from the CryENGINE MOD SDK 1.4 CryGame.dll

> A campaign co-op mod for the original Far Cry: a `Coop` multiplayer game type, a rebuilt `CryGame.dll`
> that keeps the AI on the server and replicates it, and a Windows launcher with LAN discovery. Everything was
> built and tested in a Linux container **without the game**. The DLL and launcher load and pass their tests
> under Wine, but no co-op session has been played. Code: `examples/farcry-coop`.

## Setup
- Target: Far Cry **1.4**, 32-bit `Bin32`. The SDK's game source only matches 1.4 engine binaries.
- SDK source: `github.com/fholger/farcry_vrmod` at commit `ef7b86c`. It is Crytek's MOD SDK 1.4 plus the
  small changes needed for VS2022 and the bundled STLport. The VR mod is the proof that a modern-MSVC
  CryGame.dll works with the retail 1.4 engine. Docs to read: `FCMG.pdf`, `GameSource-Readme.pdf`,
  `Tools to tweak FarCry MP.pdf`, `How to use the built in Internet simulator.pdf`.
- Toolchain on Linux: clang-cl 18 (`--target=i686-pc-windows-msvc`), lld-link, Microsoft CRT and SDK from
  `xwin --arch x86 splat`, a static CRT (`/MT`). The launcher is mingw-w64; tests run in Wine 9.

## Route and why
Native game DLL from the official SDK source. The engine already has an authoritative server, reliable and
unreliable channels, snapshots with distance priority and a bandwidth budget, and entity-state sync.
Single player is that same server with one local client. Co-op is therefore a list of single-player
assumptions to remove, not a new netcode. Rejected: an external sync process (it can't touch engine state
cleanly) and memory patching (the source is available and redistributable under the SDK EULA).

## How the game works (what we had to learn)
- **Mod loading.** `-MOD:<Name>` makes `Mods/<Name>` the root. With 1.4 the engine loads
  `Mods/<Name>/Bin32/CryGame.dll` instead of the stock DLL. The original files never need touching.
- **Memory.** `CryMemoryManager.h` routes CryGame's malloc and new to `CrySystem.dll!CryMalloc/CryFree` via
  GetProcAddress. Our static CRT therefore never mixes heaps with the engine.
- **SP vs MP.** `CXGame::IsMultiplayer()` is true unless the game is both server and client, or for a listen
  server. `CXGame::Update` passes `ESYSUPDATE_MULTIPLAYER` to `ISystem::Update`. What CrySystem skips with that
  flag is closed source; we watch `IAISystem::GetAITickCount()` at run time instead of guessing.
- **Game types.** The server runs `Scripts/<g_GameType>/GameRules.lua` (fallback `Default`, which is single
  player). Clients run `scripts/<gametype>/ClientStuff.lua` (also falls back to `Default`), take `g_GameType`
  from the server and refuse a server running another mod.
- **Levels on clients.** Both server and client run `LoadLevelCommon`: the AI system, triangulation and
  every level entity, with the same entity IDs and net presence off.
- **Replication.** Each server slot keeps every entity in its snapshot list. Unreliable updates only go out
  for entities with net presence, prioritised by distance to the viewer. Entity *state* changes (doors,
  alarms, Dead) go out reliably for any entity whose state isn't client-side.
- **Player serialisation.** `CPlayer::Write/Read` sends stance, weapon, fire mode, firing, reload, lean and
  grenade throws for actors you don't own. Health and ammo go only to the owner.
- **AI targets.** Players register with AI from Lua (`AI:RegisterWithAI(id, AIOBJECT_PLAYER, Properties)`
  reads `eye_height`, `groupid`, `species`). There is no C++ getter for agent parameters.
- **Starting sessions.** Host: `g_GameType` + `Game:LoadLevelListen(map[, mission])`. Join:
  `Game:Connect(ip)`. Server port `sv_port` defaults to 49001 and the server retries port+1 if it is busy.
- **Damage.** `CXServerRules::OnHitPlayer/OnHitObject` exist but nothing calls them. Damage reaches
  `GameRules:OnDamage(hit)` through entity scripts, which is also what Crytek's mod guide edits.
- **Lua.** 5.0 dialect (`for k,v in t do`), with compat globals (`getglobal`, `mod`) and Crytek's `sprintf`.
  Console commands are Lua strings (`AddCommand("name", "Game:Foo()")`).

## Build steps
1. `xwin --accept-license --arch x86 splat --output ~/xwin`
2. `STOCK=1 examples/farcry-coop/crygame/build.sh` builds the unmodified SDK DLL as a baseline.
3. `examples/farcry-coop/crygame/build.sh`, `launcher/build.sh`, `tests/run_all.sh`, `tools/package.sh`.

## Verification
- Both DLLs (stock and co-op): PE32 i386, exports `CreateGameInstance` and `CryModuleGetMemoryInfo`, only
  system imports. Under Wine with a stub CrySystem.dll that exports the five allocator functions, they load,
  bind the allocator and construct the game object through CryMalloc.
- Co-op rules run under real Lua 5.0.3 with mocked Server, ServerSlot, Game and System objects (45 checks:
  per-client spawn, respawn delay and auto respawn, checkpoint anchor, friendly fire, disconnect).
- Launcher under Wine against a fake install with a stub FarCry.exe that records its command line (24
  checks), including LAN discovery between two launcher processes. The launch-argument parser and LAN
  protocol are unit-tested and fuzzed under ASan.
- **Not verified:** anything inside the running game. The README in the example has a 10-step in-game test
  plan with the log lines to expect.

## Gotchas
1. **clang-cl rejects SDK headers that VS accepts.** Cause: old MSVC "friend injection" (`GetPlane`,
   `GetTransposed44` and `GetTranslationMat` are defined only as friends). Explicit specialisations also lack
   `template<>` (`Vec3_tpl<f32>` constructors, `SVariationValue<Vec3>`, `_DECLARE_SCRIPTABLEEX`). Fix: add
   namespace-scope redeclarations and `template<>`. The `_ScriptableEx.h` LINUX branch already has the
   correct macro, so select it for clang too.
2. **MASM `$labels` in inline asm** (`Cry_XOptimise.h` memcpy) fail in clang's MS-asm parser. Fix: rename
   the labels.
3. **MSVC STL refuses clang 18** (`STL1000`), even though STLport is used, because STLport wraps the native
   headers. Fix: `/D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH`.
4. **Include case and backslashes** (`#include ".\game.h"`, `"Util\GuidUtil.h"`) break on Linux. Fix:
   rewrite the include lines and add case symlinks in the build copy.
5. **`Game:LoadLevel` ends multiplayer.** It forces `g_GameType=Default` and a non-listen server, so a
   campaign level transition drops co-op. Use `LoadLevelMPServer` for a co-op aware transition.
6. **`Game:TouchCheckPoint` dereferences `GetMyPlayer()` without a NULL check.** A checkpoint on a
   dedicated server would crash. Co-op handles checkpoints before that code runs.
7. **`BasicPlayer:IsAlive()` returns a boolean** (state == "Alive"), not 1. And `_time` is not a script
   global; scripts use `System:GetCurrAsyncTime()`.
8. **Stock Lua 5.0.3 lacks `getglobal`/`mod`**, which Far Cry's interpreter has. Shim them in tests, and
   avoid `mod` in mod code.
9. **`_localplayer` is per process.** On the server it is the host, so mission triggers written for "the
   player" may only react to the host. `examples/farcry-coop/tools/audit_scripts.py` lists those spots in
   your own Scripts.pak per level.

## Open questions
- Does CrySystem skip the AI update in MP? The DLL detects it either way; the log line `[coop] AI tick count
  frozen` answers it.
- Do AI actors animate correctly on clients from snapshots alone?
- Does the closed CryNetwork.dll accept `ip:port` in `Game:Connect`?
- Behaviour of a clang-cl DLL against the real engine (the VR mod proves MSVC 2022; clang-cl is only
  load-tested).
