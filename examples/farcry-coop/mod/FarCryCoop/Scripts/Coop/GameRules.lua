-----------------------------------------------------------------------------------------------------------
-- FarCryCoop: server rules for g_GameType "Coop".
--
-- The engine loads this file as Scripts\Coop\GameRules.lua (CXServerRules::ChangeGameRules). It starts from
-- the single-player rules that ship with the user's own game (Scripts/Default/GameRules.lua inside
-- FCData/Scripts.pak), so damage, difficulty and AI hit handling stay exactly as in the campaign. On top of
-- that it adds what single player never had to handle: several clients, one player entity per client,
-- respawning, leaving, and friendly fire.
--
-- Only engine calls that the CryENGINE MOD SDK 1.4 C++ source registers are used here (Server:SpawnEntity,
-- ServerSlot:SetPlayerId/GetPlayerId/GetName/GetModel, Server:GetFirstRespawnPoint/GetNextRespawnPoint,
-- Server:RemoveEntity, Server:GetServerSlotByEntityId, Server:BroadcastText, Game:CoopGetRespawnAnchor ...).
-----------------------------------------------------------------------------------------------------------

Script:LoadScript("Scripts/Default/GameRules.lua");
if (not GameRules) then
	System:Log("[coop] ERROR: Scripts/Default/GameRules.lua did not define GameRules, using empty rules");
	GameRules = {};
end

-- Keep the single-player callbacks we wrap.
CoopSP = {};
for name, fn in GameRules do
	if (type(fn) == "function") then
		CoopSP[name] = fn;
	end
end

Game:CreateVariable("coop_respawn_delay", "5");     -- seconds before a dead player may respawn
Game:CreateVariable("coop_auto_respawn", "20");     -- seconds after which a dead player respawns anyway (0 = never)
Game:CreateVariable("coop_friendlyfire", "0");      -- 1 = players can hurt each other
Game:CreateVariable("coop_sp_connect", "0");        -- 1 = let the single-player rules spawn the first (host) client

Coop = {
	players = {},       -- [slotId] = { entityId, name, deadSince }
	connects = 0,
	spawnIndex = 0,
};

local function CoopNum(name, default)
	local v = tonumber(getglobal(name));
	if (v == nil) then return default; end
	return v;
end

local function CoopNow()
	return System:GetCurrAsyncTime();
end

local function CoopLog(text)
	System:Log("[coop] "..text);
end

-- Where a (re)spawning player goes: the last checkpoint, else a level respawn point, else next to a living
-- team mate. The index spreads players a little so they do not spawn inside each other.
function Coop:PickSpawn()
	self.spawnIndex = self.spawnIndex + 1;
	local k = self.spawnIndex;
	while (k >= 4) do k = k - 4; end
	local offset = k * 1.5;
	local anchor = Game:CoopGetRespawnAnchor();
	if (anchor) then
		return { x = anchor.x + offset, y = anchor.y, z = anchor.z + 0.2 }, { x = anchor.ax, y = anchor.ay, z = anchor.az }, "checkpoint";
	end
	local tp = Server:GetNextRespawnPoint();
	if (not tp) then tp = Server:GetFirstRespawnPoint(); end
	if (tp) then
		return { x = tp.x + offset, y = tp.y, z = tp.z + 0.2 }, { x = tp.xA, y = tp.yA, z = tp.zA }, "respawn point "..tostring(tp.name);
	end
	for slotId, p in self.players do
		local ent = System:GetEntity(p.entityId);
		if (ent and ent:IsAlive()) then
			local pos = ent:GetPos();
			return { x = pos.x + 1.5, y = pos.y + 1.5, z = pos.z + 0.5 }, ent:GetAngles(), "team mate "..p.name;
		end
	end
	CoopLog("WARNING: no checkpoint, respawn point or living player; spawning at the map origin");
	return { x = 0, y = 0, z = 100 }, { x = 0, y = 0, z = 0 }, "origin";
end

function Coop:SpawnPlayer(server_slot)
	local name = server_slot:GetName();
	local model = server_slot:GetModel();
	local pos, angles, where = self:PickSpawn();
	local ed = { classid = PLAYER_CLASS_ID, name = name, pos = pos, angles = angles };
	if (model and model ~= "") then
		ed.model = model;
	end
	local ent = Server:SpawnEntity(ed);
	if (not ent) then
		CoopLog("ERROR: could not spawn a player for '"..tostring(name).."'");
		return nil;
	end
	server_slot:SetPlayerId(ent.id);
	self.players[server_slot:GetId()] = { entityId = ent.id, name = name, deadSince = nil };
	CoopLog(sprintf("spawned '%s' as entity %d at %s (%.1f %.1f %.1f)", tostring(name), ent.id, where, pos.x, pos.y, pos.z));
	return ent;
end

function Coop:IsHumanPlayer(ent)
	return ent and ent.id and Server:GetServerSlotByEntityId(ent.id) ~= nil;
end

-----------------------------------------------------------------------------------------------------------
-- Engine callbacks (called from CXServerRules)
-----------------------------------------------------------------------------------------------------------

function GameRules:OnInit()
	if (CoopSP.OnInit) then CoopSP.OnInit(self); end
	Coop.players = {};
	Coop.connects = 0;
	Coop.spawnIndex = 0;
	CoopLog("co-op rules active on "..tostring(Game:GetLevelName()));
end

function GameRules:OnClientConnect(server_slot, requestedClassId)
	Coop.connects = Coop.connects + 1;
	CoopLog(sprintf("client '%s' connected (slot %d, ping %d)", tostring(server_slot:GetName()), server_slot:GetId(), server_slot:GetPing()));
	if (Coop.connects == 1 and CoopNum("coop_sp_connect", 0) == 1 and CoopSP.OnClientConnect) then
		local ret = CoopSP.OnClientConnect(self, server_slot, requestedClassId);
		Coop.players[server_slot:GetId()] = { entityId = server_slot:GetPlayerId(), name = server_slot:GetName() };
		return ret;
	end
	Coop:SpawnPlayer(server_slot);
	Server:BroadcastText(tostring(server_slot:GetName()).." joined the co-op session");
	return PLAYER_CLASS_ID;
end

function GameRules:OnClientDisconnect(server_slot)
	local slotId = server_slot:GetId();
	local p = Coop.players[slotId];
	local id = server_slot:GetPlayerId();
	if (p) then id = p.entityId; end
	if (id and id ~= 0) then
		Server:RemoveEntity(id);
	end
	Coop.players[slotId] = nil;
	CoopLog("client '"..tostring(server_slot:GetName()).."' left");
	Server:BroadcastText(tostring(server_slot:GetName()).." left the co-op session");
	if (CoopSP.OnClientDisconnect) then CoopSP.OnClientDisconnect(self, server_slot); end
end

function Coop:Respawn(server_slot, reason)
	local slotId = server_slot:GetId();
	local p = self.players[slotId];
	if (p and p.entityId) then
		Server:RemoveEntity(p.entityId);
	end
	local ent = self:SpawnPlayer(server_slot);
	if (ent) then
		CoopLog("respawned '"..tostring(server_slot:GetName()).."' ("..reason..")");
		if (CoopSP.OnPlayerRespawn) then CoopSP.OnPlayerRespawn(self, ent); end
	end
end

function GameRules:OnClientRequestRespawn(server_slot, requestedClassId)
	local p = Coop.players[server_slot:GetId()];
	if (not p) then
		Coop:Respawn(server_slot, "no player yet");
		return;
	end
	local ent = System:GetEntity(p.entityId);
	if (ent and ent:IsAlive()) then
		return;
	end
	if (p.deadSince and CoopNow() - p.deadSince < CoopNum("coop_respawn_delay", 5)) then
		return;
	end
	Coop:Respawn(server_slot, "requested");
end

function GameRules:OnUpdate()
	if (CoopSP.OnUpdate) then CoopSP.OnUpdate(self); end
	local auto = CoopNum("coop_auto_respawn", 20);
	for slotId, p in Coop.players do
		local ent = System:GetEntity(p.entityId);
		if (ent and not ent:IsAlive()) then
			if (not p.deadSince) then
				p.deadSince = CoopNow();
				Server:BroadcastText(tostring(p.name).." is down");
			elseif (auto > 0 and CoopNow() - p.deadSince > auto) then
				local slot = Server:GetServerSlotBySSId(slotId);
				if (slot) then Coop:Respawn(slot, "auto"); end
			end
		elseif (ent) then
			p.deadSince = nil;
		end
	end
end

-- Damage between human players is ignored unless coop_friendlyfire=1. Everything else goes to the
-- single-player rules unchanged.
function GameRules:OnDamage(hit)
	if (CoopNum("coop_friendlyfire", 0) ~= 1 and hit and hit.shooter and hit.target and hit.shooter ~= hit.target
			and Coop:IsHumanPlayer(hit.shooter) and Coop:IsHumanPlayer(hit.target)) then
		return;
	end
	if (CoopSP.OnDamage) then
		return CoopSP.OnDamage(self, hit);
	end
end

-- Callbacks the C++ may call that the single-player rules might not define.
local function CoopDefault(name, fn)
	if (not GameRules[name]) then GameRules[name] = fn; end
end
CoopDefault("OnShutdown", function(self) end);
CoopDefault("OnMapChange", function(self) end);
CoopDefault("OnKill", function(self, server_slot) end);
CoopDefault("OnPlayerRespawn", function(self, player) end);
CoopDefault("OnHitObject", function(self, hit) end);
CoopDefault("OnHitPlayer", function(self, hit) end);
CoopDefault("OnClientCmd", function(self, server_slot, cmd) end);
CoopDefault("OnClientJoinTeamRequest", function(self, server_slot, team) end);
CoopDefault("OnSpectatorSwitchModeRequest", function(self, spec) end);
CoopDefault("OnCallVote", function(self, server_slot, command, arg1) end);
CoopDefault("OnVote", function(self, server_slot, vote) end);
CoopDefault("GetPlayerScoreInfo", function(self, server_slot, stream) end);

CoopLog("Scripts/Coop/GameRules.lua loaded");
