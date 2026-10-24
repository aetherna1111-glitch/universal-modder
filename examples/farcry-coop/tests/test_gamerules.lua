-- Exercises mod/FarCryCoop/Scripts/Coop/GameRules.lua under a real Lua 5.0 interpreter with mocked
-- engine objects (Server, ServerSlot, Game, System, Script). The mocks only model the calls the rules use,
-- with the shapes the CryENGINE MOD SDK 1.4 C++ registers. Run: lua test_gamerules.lua <path to GameRules.lua>

local RULES = arg and arg[1] or "../mod/FarCryCoop/Scripts/Coop/GameRules.lua"

-- Far Cry compat globals the scripts rely on.
sprintf = string.format
getglobal = getglobal or function(name) return _G[name] end
setglobal = setglobal or function(name, value) _G[name] = value end
PLAYER_CLASS_ID = 1

local fails, checks = 0, 0
local function check(cond, what)
	checks = checks + 1
	if not cond then
		fails = fails + 1
		print("FAIL: "..what)
	end
end

-- clock -------------------------------------------------------------------------------------------------
local now = 100
System = {
	logs = {},
	Log = function(self, s) table.insert(self.logs, s) end,
	GetCurrAsyncTime = function(self) return now end,
	GetEntity = function(self, id) return Entities[id] end,
}
local function logged(pattern)
	for _, l in ipairs(System.logs) do
		if string.find(l, pattern, 1, true) then return true end
	end
	return false
end

-- entities ----------------------------------------------------------------------------------------------
Entities = {}
local nextId = 1000
local function NewEntity(ed)
	nextId = nextId + 1
	local e = { id = nextId, name = ed.name, classid = ed.classid, pos = ed.pos, angles = ed.angles, alive = true }
	function e:IsAlive() return self.alive end
	function e:GetPos() return { x = self.pos.x, y = self.pos.y, z = self.pos.z } end
	function e:GetAngles() return self.angles end
	Entities[e.id] = e
	return e
end

-- server slots --------------------------------------------------------------------------------------------
Slots = {}
local function NewSlot(id, name)
	local s = { id = id, name = name, playerId = 0 }
	function s:GetId() return self.id end
	function s:GetName() return self.name end
	function s:GetModel() return "" end
	function s:GetPing() return 20 end
	function s:SetPlayerId(pid) self.playerId = pid end
	function s:GetPlayerId() return self.playerId end
	Slots[id] = s
	return s
end

local respawnPoints = {}
Server = {
	removed = {}, broadcasts = {},
	SpawnEntity = function(self, ed) return NewEntity(ed) end,
	RemoveEntity = function(self, id) table.insert(self.removed, id); Entities[id] = nil end,
	GetServerSlotByEntityId = function(self, id)
		for _, s in pairs(Slots) do if s.playerId == id then return s end end
	end,
	GetServerSlotBySSId = function(self, id) return Slots[id] end,
	GetFirstRespawnPoint = function(self) return respawnPoints[1] end,
	GetNextRespawnPoint = function(self) return respawnPoints[1] end,
	BroadcastText = function(self, t) table.insert(self.broadcasts, t) end,
}

local anchor = nil
local cvars = {}
Game = {
	CreateVariable = function(self, name, default)
		if getglobal(name) == nil then setglobal(name, default) end
	end,
	CoopGetRespawnAnchor = function(self) return anchor end,
	GetLevelName = function(self) return "pier" end,
}

-- the user's single-player rules (stand-in) ---------------------------------------------------------------
local spDamage, spInit, spUpdate = {}, 0, 0
Script = {
	LoadScript = function(self, path)
		check(path == "Scripts/Default/GameRules.lua", "rules load the single-player rules first")
		GameRules = {
			OnInit = function(self) spInit = spInit + 1 end,
			OnUpdate = function(self) spUpdate = spUpdate + 1 end,
			OnDamage = function(self, hit) table.insert(spDamage, hit) end,
		}
	end,
}

-- load ----------------------------------------------------------------------------------------------------
dofile(RULES)
check(GameRules ~= nil and CoopSP.OnDamage ~= nil, "single-player callbacks kept")
for _, name in ipairs({ "OnInit", "OnUpdate", "OnShutdown", "OnClientConnect", "OnClientDisconnect",
		"OnClientRequestRespawn", "OnPlayerRespawn", "OnHitPlayer", "OnHitObject", "OnKill", "OnMapChange",
		"OnClientCmd", "OnClientJoinTeamRequest", "OnSpectatorSwitchModeRequest", "OnCallVote", "OnVote",
		"GetPlayerScoreInfo", "OnDamage" }) do
	check(type(GameRules[name]) == "function", "GameRules."..name.." defined (C++ calls it)")
end

GameRules:OnInit()
check(spInit == 1, "OnInit forwards to the single-player rules")

-- two players connect, no checkpoint yet: level respawn point --------------------------------------------
respawnPoints[1] = { x = 10, y = 20, z = 30, xA = 0, yA = 0, zA = 90, name = "Respawn0" }
local host = NewSlot(0, "Host")
local guest = NewSlot(1, "Guest")
check(GameRules:OnClientConnect(host, 1) == PLAYER_CLASS_ID, "connect returns the player class id")
check(GameRules:OnClientConnect(guest, 1) == PLAYER_CLASS_ID, "second client connects")
local hostEnt, guestEnt = Entities[host.playerId], Entities[guest.playerId]
check(hostEnt and guestEnt and hostEnt ~= guestEnt, "each client owns its own player entity")
check(hostEnt.classid == PLAYER_CLASS_ID and hostEnt.name == "Host", "player spawned with class and name")
check(hostEnt.pos.y == 20 and math.abs(hostEnt.pos.z - 30.2) < 0.01, "spawned at the level respawn point")
check(hostEnt.pos.x ~= guestEnt.pos.x, "players do not spawn inside each other")
check(Server.broadcasts[2] == "Guest joined the co-op session", "join is announced")

-- friendly fire ---------------------------------------------------------------------------------------------
local ai = NewEntity({ name = "Merc1", pos = { x = 0, y = 0, z = 0 } })
GameRules:OnDamage({ shooter = hostEnt, target = guestEnt, damage = 50 })
check(table.getn(spDamage) == 0, "player-on-player damage blocked with coop_friendlyfire=0")
GameRules:OnDamage({ shooter = ai, target = guestEnt, damage = 50 })
check(table.getn(spDamage) == 1, "AI damage reaches the single-player rules")
GameRules:OnDamage({ shooter = hostEnt, target = ai, damage = 50 })
check(table.getn(spDamage) == 2, "player damage to AI reaches the single-player rules")
GameRules:OnDamage({ shooter = guestEnt, target = guestEnt, damage = 50 })
check(table.getn(spDamage) == 3, "self damage (grenades, falls) is not blocked")
setglobal("coop_friendlyfire", "1")
GameRules:OnDamage({ shooter = hostEnt, target = guestEnt, damage = 50 })
check(table.getn(spDamage) == 4, "coop_friendlyfire=1 lets player damage through")
setglobal("coop_friendlyfire", "0")

-- death and respawn -----------------------------------------------------------------------------------------
guestEnt.alive = false
GameRules:OnUpdate()
check(Coop.players[1].deadSince == now, "death noticed on update")
check(Server.broadcasts[table.getn(Server.broadcasts)] == "Guest is down", "death announced")
now = now + 2
GameRules:OnClientRequestRespawn(guest, 1)
check(guest.playerId == guestEnt.id, "no respawn before coop_respawn_delay")
anchor = { x = 500, y = 600, z = 70, ax = 0, ay = 0, az = 180, checkpoint = 3 }
now = now + 4
GameRules:OnClientRequestRespawn(guest, 1)
local newGuest = Entities[guest.playerId]
check(newGuest and newGuest ~= guestEnt, "respawn after the delay gives a new player entity")
check(Entities[guestEnt.id] == nil, "the dead entity is removed")
check(newGuest.pos.y == 600 and newGuest.angles.z == 180, "respawn uses the checkpoint anchor")
GameRules:OnClientRequestRespawn(host, 1)
check(host.playerId == hostEnt.id, "a living player is not respawned on request")

-- auto respawn ------------------------------------------------------------------------------------------------
hostEnt.alive = false
GameRules:OnUpdate()
now = now + 25
GameRules:OnUpdate()
check(host.playerId ~= hostEnt.id and Entities[host.playerId].alive, "auto respawn after coop_auto_respawn seconds")
check(spUpdate == 3, "OnUpdate forwards to the single-player rules")

-- disconnect -----------------------------------------------------------------------------------------------
local leaving = guest.playerId
GameRules:OnClientDisconnect(guest)
check(Entities[leaving] == nil and Coop.players[1] == nil, "leaving client's player removed")
check(Server.broadcasts[table.getn(Server.broadcasts)] == "Guest left the co-op session", "leave announced")

-- no anchor, no respawn point, no living player: origin with a warning --------------------------------------
anchor, respawnPoints[1] = nil, nil
Entities[host.playerId].alive = false
local late = NewSlot(2, "Late")
GameRules:OnClientConnect(late, 1)
check(Entities[late.playerId].pos.z == 100 and logged("WARNING: no checkpoint"), "fallback spawn is logged")

print(string.format("%d checks, %d failed", checks, fails))
if fails > 0 then os.exit(1) end
