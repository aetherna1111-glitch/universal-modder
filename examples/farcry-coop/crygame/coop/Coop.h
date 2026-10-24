//////////////////////////////////////////////////////////////////////
//
//  FarCryCoop: co-op extensions for the Far Cry 1.4 CryGame.dll (CryENGINE MOD SDK 1.4).
//
//  Everything here is inactive unless the server runs g_GameType "Coop" in multiplayer, so a stock
//  FFA/TDM/Assault/single-player session behaves exactly like the unmodified SDK DLL.
//
//  What it does while active:
//  - server: keeps the AI system ticking in multiplayer (only if the engine stops ticking it),
//    gives AI actors net presence so their state replicates to clients, registers human players
//    as AI targets, turns campaign checkpoints into a shared co-op respawn anchor;
//  - pure client: disables the local copies of AI agents (the server's AI drives them);
//  - both: "coop_status" console command and [coop] log lines in log.txt.
//
//////////////////////////////////////////////////////////////////////
#ifndef FARCRY_COOP_H
#define FARCRY_COOP_H

class CXGame;
struct IEntity;
struct IFunctionHandler;
struct IScriptSystem;

namespace Coop
{
	//! Register cvars/commands. Call once after CXGame::InitConsoleVars().
	void Init(CXGame *pGame);
	void Shutdown();

	//! True while this process runs a "Coop" multiplayer session (as server, client or both).
	bool IsActive();

	//! Every frame, right after ISystem::Update().
	void PostSystemUpdate();

	//! End of CXSystemServer::LoadLevel (after the level and its entities are loaded).
	void OnServerLevelLoaded();

	//! Game:TouchCheckPoint. Returns true when co-op handled it (no single-player save must happen).
	bool OnTouchCheckPoint(int nCheckPoint, const float *pos, const float *angles);

	//! Script functions exposed on the Game table (see ScriptObjectGame patch).
	int Script_GetRespawnAnchor(IFunctionHandler *pH, IScriptSystem *pSS);
	int Script_IsActive(IFunctionHandler *pH);
	int Script_Status(IFunctionHandler *pH);
}

#endif // FARCRY_COOP_H
