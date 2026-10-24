//////////////////////////////////////////////////////////////////////
//
//  FarCryCoop: co-op extensions for the Far Cry 1.4 CryGame.dll. See Coop.h.
//
//  This file is new code written for the FarCryCoop mod. It builds against the CryENGINE MOD SDK 1.4
//  headers and is distributed under the SDK license terms (EULA.txt), like the rest of the DLL.
//
//////////////////////////////////////////////////////////////////////
#include "stdafx.h"
#include "Coop.h"
#include "CoopArgs.h"
#include "Game.h"
#include "XServer.h"
#include "XServerSlot.h"
#include "XPlayer.h"
#include "XObjectProxy.h"
#include <IAISystem.h>
#include <IAgent.h>
#include <ITimer.h>
#include <set>

namespace
{
	const char *COOP_GAMETYPE = "Coop";
	const float SCAN_INTERVAL = 0.5f;				// seconds between entity scans
	const float AI_STALL_SECONDS = 2.0f;		// AI tick count frozen this long => engine is not ticking AI
	const float AI_CHECK_WINDOW = 2.0f;

	CXGame *g_pGame = 0;

	ICVar *coop_ai_server_update = 0;
	ICVar *coop_ai_netpresence = 0;
	ICVar *coop_client_ai_freeze = 0;
	ICVar *coop_player_ai = 0;
	ICVar *coop_debug = 0;

	struct SState
	{
		string level;										// level the state belongs to
		float fNextScan;

		// AI tick watchdog (server)
		int nLastTick;
		float fLastTickChange;
		bool bFallback;									// we call IAISystem::Update ourselves
		int nWindowStartTick;
		int nWindowCalls;
		float fWindowStart;
		int nFallbackCalls;

		// bookkeeping
		std::set<EntityId> netEnabled;	// AI actors we gave net presence (server)
		std::set<EntityId> playerAI;		// human players we registered as AI targets (server)
		std::set<EntityId> frozen;			// AI agents disabled on this pure client

		// co-op respawn anchor (server)
		bool bAnchor;
		Vec3 vAnchorPos, vAnchorAngles;
		int nAnchorCheckPoint;

		void Reset(const char *szLevel)
		{
			level = szLevel ? szLevel : "";
			fNextScan = 0;
			nLastTick = -1; fLastTickChange = -1;
			bFallback = false; nWindowStartTick = 0; nWindowCalls = 0; fWindowStart = 0; nFallbackCalls = 0;
			netEnabled.clear(); playerAI.clear(); frozen.clear();
			bAnchor = false; vAnchorPos.Set(0,0,0); vAnchorAngles.Set(0,0,0); nAnchorCheckPoint = -1;
		}
	};
	SState g_state;

	// Launch request from FarCryCoop.exe (command line), executed once the main menu is up.
	SCoopLaunchRequest g_request;
	int g_nRequestFrames = -1;			// frames left before executing the request, -1 = none
	const int REQUEST_DELAY_FRAMES = 60;

	ILog *Log() { return g_pGame->GetSystem()->GetILog(); }

	float Now() { return g_pGame->GetSystem()->GetITimer()->GetCurrTime(); }

	CPlayer *GetPlayer(IEntity *pEnt)
	{
		if (!pEnt)
			return 0;
		IEntityContainer *pCnt = pEnt->GetContainer();
		CPlayer *pPlayer = 0;
		if (pCnt && pCnt->QueryContainerInterface(CIT_IPLAYER, (void **)&pPlayer))
			return pPlayer;
		return 0;
	}

	bool IsLevelRunning()
	{
		if (g_pGame->m_pServer && g_pGame->m_pServer->m_bIsLoadingLevel)
			return false;
		return !g_pGame->m_currentLevel.empty();
	}

	//////////////////////////////////////////////////////////////////////
	// Server: keep AI simulation running in multiplayer.
	//
	// ISystem::Update receives ESYSUPDATE_MULTIPLAYER in MP; whether CrySystem.dll then skips the AI
	// update is not visible in the SDK. Instead of guessing, watch IAISystem::GetAITickCount(): if it does
	// not move for AI_STALL_SECONDS while a level runs, tick the AI ourselves; if it moves faster than our
	// own calls explain, the engine is ticking it too, so stop.
	void UpdateAIWatchdog(float now)
	{
		IAISystem *pAI = g_pGame->GetSystem()->GetAISystem();
		if (!pAI || !coop_ai_server_update->GetIVal() || !IsLevelRunning())
		{
			g_state.bFallback = false;
			return;
		}

		int nTick = pAI->GetAITickCount();
		if (!g_state.bFallback)
		{
			if (nTick != g_state.nLastTick || g_state.fLastTickChange < 0)
			{
				g_state.nLastTick = nTick;
				g_state.fLastTickChange = now;
			}
			else if (now - g_state.fLastTickChange > AI_STALL_SECONDS)
			{
				g_state.bFallback = true;
				g_state.nWindowStartTick = nTick;
				g_state.nWindowCalls = 0;
				g_state.fWindowStart = now;
				Log()->Log("[coop] AI tick count frozen at %d for %.1fs in multiplayer: server now updates the AI system itself",
					nTick, now - g_state.fLastTickChange);
			}
			return;
		}

		pAI->Update();
		++g_state.nWindowCalls;
		++g_state.nFallbackCalls;

		if (now - g_state.fWindowStart >= AI_CHECK_WINDOW)
		{
			int nGained = pAI->GetAITickCount() - g_state.nWindowStartTick;
			if (coop_debug->GetIVal())
				Log()->Log("[coop] AI watchdog: %d ticks for %d own updates in %.1fs", nGained, g_state.nWindowCalls, now - g_state.fWindowStart);
			if (nGained > g_state.nWindowCalls + g_state.nWindowCalls / 2 + 3)
			{
				// The engine ticks the AI as well: hand it back.
				g_state.bFallback = false;
				g_state.nLastTick = pAI->GetAITickCount();
				g_state.fLastTickChange = now;
				Log()->Log("[coop] AI ticks without us (%d ticks for %d calls): AI watchdog back to observing", nGained, g_state.nWindowCalls);
				return;
			}
			g_state.nWindowStartTick = pAI->GetAITickCount();
			g_state.nWindowCalls = 0;
			g_state.fWindowStart = now;
		}
	}

	//////////////////////////////////////////////////////////////////////
	// Server: register a human player as an AI target (what AI:RegisterWithAI(id, AIOBJECT_PLAYER, Properties)
	// does from Lua in single player), reading eye_height/groupid/species from the entity's Properties table.
	bool RegisterPlayerWithAI(IEntity *pEnt)
	{
		IScriptSystem *pSS = g_pGame->GetScriptSystem();
		AIObjectParameters params;
		params.bUsePathfindOutdoors = true;
		params.fEyeHeight = 1.7f;
		params.m_sParamStruct.m_nGroup = 0;
		params.m_sParamStruct.m_nSpecies = 0;

		IScriptObject *pScript = pEnt->GetScriptObject();
		if (pScript)
		{
			_SmartScriptObject pProps(pSS, true);
			if (pScript->GetValue("Properties", pProps))
			{
				pProps->GetValue("eye_height", params.fEyeHeight);
				pProps->GetValue("groupid", params.m_sParamStruct.m_nGroup);
				pProps->GetValue("species", params.m_sParamStruct.m_nSpecies);
			}
		}

		params.pProxy = new CXObjectProxy(pEnt, pSS);
		if (!pEnt->RegisterInAISystem(AIOBJECT_PLAYER, params))
		{
			Log()->Log("[coop] could not register player '%s' (%d) with the AI system", pEnt->GetName(), (int)pEnt->GetId());
			return false;
		}
		if (pEnt->GetAI())
			pEnt->GetAI()->SetName(pEnt->GetName());
		Log()->Log("[coop] registered player '%s' (%d) as AI target (species %d, group %d, eye %.2f)",
			pEnt->GetName(), (int)pEnt->GetId(), params.m_sParamStruct.m_nSpecies, params.m_sParamStruct.m_nGroup, params.fEyeHeight);
		return true;
	}

	void ServerScan()
	{
		IEntitySystem *pES = g_pGame->GetSystem()->GetIEntitySystem();

		// AI actors: replicate them to clients.
		if (coop_ai_netpresence->GetIVal())
		{
			IEntityItPtr pIt = pES->GetEntityIterator();
			pIt->MoveFirst();
			IEntity *pEnt;
			int nNew = 0;
			while ((pEnt = pIt->Next()) != NULL)
			{
				if (pEnt->IsGarbage() || pEnt->GetNetPresence())
					continue;
				CPlayer *pPlayer = GetPlayer(pEnt);
				if (!pPlayer || !pPlayer->IsAI())
					continue;
				pEnt->SetNetPresence(true);
				g_state.netEnabled.insert(pEnt->GetId());
				++nNew;
			}
			if (nNew && coop_debug->GetIVal())
				Log()->Log("[coop] net presence enabled on %d AI actors (%d total)", nNew, (int)g_state.netEnabled.size());
		}

		// Human players: make sure AI can perceive every one of them.
		if (coop_player_ai->GetIVal() && g_pGame->m_pServer)
		{
			CXServer::XSlotMap &slots = g_pGame->m_pServer->GetSlotsMap();
			for (CXServer::XSlotMap::iterator it = slots.begin(); it != slots.end(); ++it)
			{
				EntityId id = it->second->GetPlayerId();
				if (id == INVALID_WID)
					continue;
				IEntity *pEnt = pES->GetEntity(id);
				CPlayer *pPlayer = GetPlayer(pEnt);
				if (!pPlayer || pPlayer->IsAI() || !pPlayer->IsAlive() || pEnt->GetAI())
					continue;
				if (RegisterPlayerWithAI(pEnt))
					g_state.playerAI.insert(id);
			}
		}
	}

	//////////////////////////////////////////////////////////////////////
	// Pure client: the level loader created local AI agents for every enemy. The server simulates them and
	// streams their state, so the local copies must not think or move on their own.
	void ClientScan()
	{
		if (!coop_client_ai_freeze->GetIVal())
			return;
		IEntitySystem *pES = g_pGame->GetSystem()->GetIEntitySystem();
		IEntityItPtr pIt = pES->GetEntityIterator();
		pIt->MoveFirst();
		IEntity *pEnt;
		int nNew = 0;
		while ((pEnt = pIt->Next()) != NULL)
		{
			if (pEnt->IsGarbage())
				continue;
			IAIObject *pAI = pEnt->GetAI();
			if (!pAI || pAI->GetType() == AIOBJECT_PLAYER)
				continue;
			CPlayer *pPlayer = GetPlayer(pEnt);
			if (!pPlayer || !pPlayer->IsAI())
				continue;
			if (g_state.frozen.find(pEnt->GetId()) != g_state.frozen.end())
				continue;
			pAI->IsEnabled(false);
			g_state.frozen.insert(pEnt->GetId());
			++nNew;
		}
		if (nNew)
			Log()->Log("[coop] client: disabled %d local AI agents (server-driven), %d total", nNew, (int)g_state.frozen.size());
	}

	void SetCVar(const char *name, const char *value)
	{
		ICVar *pVar = g_pGame->GetSystem()->GetIConsole()->GetCVar(name);
		if (pVar)
			pVar->Set(value);
		else
			Log()->Log("[coop] launch: console variable %s not found", name);
	}

	void ExecuteLaunchRequest()
	{
		IScriptSystem *pSS = g_pGame->GetScriptSystem();
		char lua[256];
		if (g_request.type == COOP_REQ_HOST)
		{
			char num[16];
			SetCVar("g_GameType", COOP_GAMETYPE);
			if (g_request.maxPlayers) { sprintf(num, "%d", g_request.maxPlayers); SetCVar("sv_maxplayers", num); }
			if (g_request.port) { sprintf(num, "%d", g_request.port); SetCVar("sv_port", num); }
			if (g_request.name[0]) SetCVar("sv_name", g_request.name);
			if (g_request.password[0]) SetCVar("sv_password", g_request.password);
			if (g_request.mission[0])
				_snprintf(lua, sizeof(lua), "Game:LoadLevelListen(\"%s\",\"%s\")", g_request.map, g_request.mission);
			else
				_snprintf(lua, sizeof(lua), "Game:LoadLevelListen(\"%s\")", g_request.map);
		}
		else
			_snprintf(lua, sizeof(lua), "Game:Connect(\"%s\")", g_request.address);
		lua[sizeof(lua) - 1] = 0;
		Log()->Log("[coop] launch request: %s", lua);
		pSS->ExecuteBuffer(lua, strlen(lua));
	}

	void UpdateLaunchRequest()
	{
		if (g_nRequestFrames < 0)
			return;
		// Wait for the menu: no level and no server yet (Game:Connect / LoadLevelCS replace any idle client).
		if (!g_pGame->m_currentLevel.empty() || g_pGame->m_pServer)
		{
			Log()->Log("[coop] launch request dropped: a session already exists");
			g_nRequestFrames = -1;
			return;
		}
		if (--g_nRequestFrames > 0)
			return;
		g_nRequestFrames = -1;
		ExecuteLaunchRequest();
	}

	void FormatStatus(char *buf, size_t size)
	{
		const char *role = !g_pGame->IsMultiplayer() ? "not in multiplayer"
			: (g_pGame->IsServer() && g_pGame->IsClient()) ? "host (listen server)"
			: g_pGame->IsServer() ? "dedicated server" : "client";
		int nSlots = g_pGame->m_pServer ? (int)g_pGame->m_pServer->GetSlotsMap().size() : 0;
		IAISystem *pAI = g_pGame->GetSystem()->GetAISystem();
		_snprintf(buf, size,
			"[coop] active=%d role=%s gametype=%s level=%s slots=%d aiTick=%d aiFallback=%d(%d calls) "
			"aiNetPresence=%d playersAsAITargets=%d clientFrozenAI=%d anchor=%s(cp %d)",
			(int)Coop::IsActive(), role, g_pGame->g_GameType->GetString(), g_pGame->m_currentLevel.c_str(), nSlots,
			pAI ? pAI->GetAITickCount() : -1, (int)g_state.bFallback, g_state.nFallbackCalls,
			(int)g_state.netEnabled.size(), (int)g_state.playerAI.size(), (int)g_state.frozen.size(),
			g_state.bAnchor ? "set" : "none", g_state.nAnchorCheckPoint);
		buf[size - 1] = 0;
	}
}

//////////////////////////////////////////////////////////////////////
void Coop::Init(CXGame *pGame)
{
	g_pGame = pGame;
	g_state.Reset("");
	IConsole *pConsole = pGame->GetSystem()->GetIConsole();
	coop_ai_server_update = pConsole->CreateVariable("coop_ai_server_update", 1, 0,
		"Co-op server: tick the AI system when the engine does not tick it in multiplayer (1=auto, 0=off)");
	coop_ai_netpresence = pConsole->CreateVariable("coop_ai_netpresence", 1, 0,
		"Co-op server: give AI actors net presence so they replicate to clients");
	coop_client_ai_freeze = pConsole->CreateVariable("coop_client_ai_freeze", 1, 0,
		"Co-op client: disable local AI agents, the server drives them");
	coop_player_ai = pConsole->CreateVariable("coop_player_ai", 1, 0,
		"Co-op server: register every human player as an AI target");
	coop_debug = pConsole->CreateVariable("coop_debug", 0, 0, "Co-op: verbose [coop] log lines");
	pConsole->AddCommand("coop_status", "Game:CoopStatus()", 0, "Co-op: print the co-op session state");

	int nReq = Coop_ParseLaunchArgs(GetCommandLineA(), &g_request);
	if (nReq > 0)
	{
		g_nRequestFrames = REQUEST_DELAY_FRAMES;
		if (g_request.type == COOP_REQ_HOST)
			Log()->Log("[coop] launch request: host map '%s' mission '%s'", g_request.map, g_request.mission);
		else
			Log()->Log("[coop] launch request: join '%s'", g_request.address);
	}
	else if (nReq < 0)
		Log()->LogError("[coop] ignoring co-op launch arguments: %s", g_request.error);
	Log()->Log("[coop] FarCryCoop CryGame.dll loaded (co-op activates with g_GameType=%s in multiplayer)", COOP_GAMETYPE);
}

void Coop::Shutdown()
{
	g_pGame = 0;
}

bool Coop::IsActive()
{
	if (!g_pGame || !g_pGame->g_GameType)
		return false;
	return stricmp(g_pGame->g_GameType->GetString(), COOP_GAMETYPE) == 0 && g_pGame->IsMultiplayer();
}

void Coop::PostSystemUpdate()
{
	if (g_pGame)
		UpdateLaunchRequest();
	if (!IsActive())
		return;

	if (g_state.level != g_pGame->m_currentLevel)
		g_state.Reset(g_pGame->m_currentLevel.c_str());

	float now = Now();
	if (g_pGame->IsServer())
		UpdateAIWatchdog(now);

	if (now < g_state.fNextScan || !IsLevelRunning())
		return;
	g_state.fNextScan = now + SCAN_INTERVAL;

	if (g_pGame->IsServer())
		ServerScan();
	else if (g_pGame->IsClient())
		ClientScan();
}

void Coop::OnServerLevelLoaded()
{
	if (!IsActive())
		return;
	g_state.Reset(g_pGame->m_currentLevel.c_str());
	Log()->Log("[coop] server loaded level '%s' mission '%s'", g_pGame->m_currentLevel.c_str(), g_pGame->m_currentMission.c_str());
}

bool Coop::OnScriptLoadLevel(const char *szMap, const char *szMission)
{
	if (!IsActive())
		return false;
	if (!szMap || !szMap[0] || strlen(szMap) > 200 || (szMission && strlen(szMission) > 200))
	{
		Log()->Log("[coop] ignoring Game:LoadLevel with an invalid level name");
		return true;
	}
	if (!g_pGame->m_pServer)
	{
		Log()->Log("[coop] client: ignoring Game:LoadLevel(%s), the server changes the level", szMap);
		return true;
	}
	char msg[300];
	_snprintf(msg, sizeof(msg), "Level complete: loading %s", szMap);
	msg[sizeof(msg) - 1] = 0;
	g_pGame->m_pServer->BroadcastText(msg);
	Log()->Log("[coop] level transition to '%s' mission '%s': changing level on the co-op server, clients stay connected",
		szMap, szMission ? szMission : "");
	// Same call as Game:LoadLevelMPServer (keep the server and its clients, listen server), g_GameType stays Coop.
	string map = szMap, mission = szMission ? szMission : "";
	g_pGame->LoadLevelCS(true, map.c_str(), mission.c_str(), true);
	return true;
}

bool Coop::OnTouchCheckPoint(int nCheckPoint, const float *pos, const float *angles)
{
	if (!IsActive())
		return false;
	if (g_pGame->m_pServer)
	{
		g_state.bAnchor = true;
		g_state.vAnchorPos.Set(pos[0], pos[1], pos[2]);
		g_state.vAnchorAngles.Set(angles[0], angles[1], angles[2]);
		g_state.nAnchorCheckPoint = nCheckPoint;
		Log()->Log("[coop] checkpoint %d reached: co-op respawn point (%.1f %.1f %.1f)", nCheckPoint, pos[0], pos[1], pos[2]);
		g_pGame->m_pServer->BroadcastText("Checkpoint reached: co-op respawn point updated");
	}
	return true;
}

int Coop::Script_GetRespawnAnchor(IFunctionHandler *pH, IScriptSystem *pSS)
{
	if (!g_state.bAnchor)
		return pH->EndFunctionNull();
	_SmartScriptObject pRes(pSS);
	pRes->SetValue("x", g_state.vAnchorPos.x);
	pRes->SetValue("y", g_state.vAnchorPos.y);
	pRes->SetValue("z", g_state.vAnchorPos.z);
	pRes->SetValue("ax", g_state.vAnchorAngles.x);
	pRes->SetValue("ay", g_state.vAnchorAngles.y);
	pRes->SetValue("az", g_state.vAnchorAngles.z);
	pRes->SetValue("checkpoint", g_state.nAnchorCheckPoint);
	return pH->EndFunction(*pRes);
}

int Coop::Script_IsActive(IFunctionHandler *pH)
{
	return pH->EndFunction(IsActive());
}

int Coop::Script_Status(IFunctionHandler *pH)
{
	char buf[512];
	FormatStatus(buf, sizeof(buf));
	g_pGame->GetSystem()->GetIConsole()->PrintLine(buf);
	Log()->Log("%s", buf);
	return pH->EndFunction(buf);
}
