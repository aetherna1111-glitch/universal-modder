FarCryCoop 0.1.0 - experimental co-op for Far Cry (2004), patch 1.4
====================================================================

This folder is installed by FarCryCoop.exe into <Far Cry>\Mods\FarCryCoop. It contains:

  Bin32\CryGame.dll          the game DLL rebuilt from the CryENGINE MOD SDK 1.4 source with co-op hooks.
                             Far Cry 1.4 loads it instead of its own only while this mod is active
                             (-MOD:FarCryCoop); the original Bin32\CryGame.dll is never touched.
  Scripts\Coop\GameRules.lua the "Coop" game type: one player per client, respawn, friendly fire.
  EULA.txt                   the CryENGINE MOD SDK license that covers the DLL (it must ship with it).
  ModDesc.txt                text for the game's Mods menu.

Start sessions with FarCryCoop.exe, or by hand from Bin32:
  host:  FarCry.exe -MOD:FarCryCoop -coophost:<level>[:<mission>] [-coopmax:4] [-coopport:49001]
  join:  FarCry.exe -MOD:FarCryCoop -coopjoin:<host ip>

Console (~): coop_status prints the co-op state. Server settings: coop_friendlyfire, coop_respawn_delay,
coop_auto_respawn, coop_ai_server_update, coop_ai_netpresence, coop_player_ai, coop_client_ai_freeze,
coop_debug. All [coop] lines go to log.txt in the Far Cry folder.

STATUS: built and load-tested, NOT yet tested inside the running game. See the project README for what is
verified and what is not.

Unofficial. Far Cry is a trademark of Ubisoft Entertainment; CryENGINE is a trademark of Crytek GmbH.
