/* FarCryCoop launcher: the user's Far Cry install, the mod folder inside it, and starting the game. */
#ifndef FARCRY_COOP_GAME_H
#define FARCRY_COOP_GAME_H

#include <windows.h>
#include "util.h"

#define MOD_NAME "FarCryCoop"
#define MAX_LEVELS 128
#define LEVEL_NAME_MAX 64

typedef struct GameInfo
{
	char root[FCC_PATH];        /* Far Cry folder (holds Bin32, FCData, Levels, Mods) */
	char exe[FCC_PATH];         /* root\Bin32\FarCry.exe */
	int hasVersion;
	unsigned ver[4];            /* FarCry.exe file version */
	int is14;                   /* file version 1.4.x.x */
	char modDir[FCC_PATH];      /* root\Mods\FarCryCoop */
	int modInstalled;           /* modDir holds Bin32\CryGame.dll */
	char modBuild[17];          /* build id of the installed mod, "" if not installed */
} GameInfo;

/* Fills g for a candidate folder. Returns 1 if it is a Far Cry install (Bin32\FarCry.exe present). */
int game_probe(const char *root, GameInfo *g);

/* Looks for an install: next to the launcher, its parent, the saved setting, Steam libraries, GOG and the
 * Windows uninstall list. Every candidate is checked with game_probe. Returns 1 and fills root. */
int game_autodetect(char *root, size_t size);

/* Remembers the folder in FarCryCoop.ini next to the launcher. */
void game_save_setting(const char *root);

/* Level folders under root\Levels with usable names. Returns the count. */
int game_list_levels(const GameInfo *g, char names[][LEVEL_NAME_MAX], int max);

/* Mod files shipped with the launcher: <launcher dir>\mod\FarCryCoop. */
int mod_source_dir(char *out, size_t size);

/* 64-bit FNV-1a over every file (relative path + bytes) under dir, as 16 hex chars. Returns 0 if empty. */
int mod_build_id(const char *dir, char hex[17]);

/* Copies the shipped mod into root\Mods\FarCryCoop. An existing, different copy is renamed to
 * FarCryCoop.backup-<time> first. Only that folder is ever written. Returns 1 on success. */
int mod_install(GameInfo *g, char *why, size_t whySize);

/* Starts Bin32\FarCry.exe -MOD:FarCryCoop <extraArgs> in Bin32. With dryRun only logs the command line.
 * Returns 1 on success and the process handle (caller closes it). */
int game_launch(const GameInfo *g, const char *extraArgs, int dryRun, HANDLE *process, DWORD *pid);

#endif
