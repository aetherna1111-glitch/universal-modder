/*
 * FarCryCoop.exe: launcher for the FarCryCoop mod for Far Cry (2004), patch 1.4.
 *
 * It finds the user's Far Cry install, installs Mods\FarCryCoop (backing up an older copy), and starts the
 * original Bin32\FarCry.exe with -MOD:FarCryCoop and a co-op host or join request. The co-op session itself
 * runs in Far Cry's own netcode; the launcher only helps players find each other on the LAN and checks that
 * they run the same mod build. It never modifies the game's own files.
 *
 * Without arguments it shows a window. Command line (also used by the automated tests):
 *   --game DIR            Far Cry folder (default: auto-detect)
 *   --check               print what was found and exit (exit code 0 = ready)
 *   --install             install/update Mods\FarCryCoop
 *   --host MAP            host: [--mission M] [--max N] [--port P] [--name S] [--password X]
 *   --join ADDRESS        join a host
 *   --dry-run             print the Far Cry command line instead of starting it
 *   --ignore-version      start even if FarCry.exe is not version 1.4
 *   --no-lobby            do not answer LAN probes while hosting
 *   --discover [--timeout MS] [--target IP]    list co-op hosts on the LAN
 *   --lobby-serve SEC     answer LAN probes for SEC seconds without starting the game (testing)
 *       with --name/--map/--port/--max/--build
 */
#include <winsock2.h>
#include <windows.h>
#include <shlobj.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "util.h"
#include "game.h"
#include "lobby_proto.h"
#include "lobby_net.h"

#define FCC_VERSION "0.2.0"
#define DEFAULT_GAME_PORT 49001

/* ------------------------------------------------------------------------------------------------------ */
/* shared helpers                                                                                          */

typedef struct HostOptions
{
	char map[LOBBY_FIELD_MAX], mission[LOBBY_FIELD_MAX], name[LOBBY_FIELD_MAX], password[LOBBY_FIELD_MAX];
	int maxPlayers, port;
} HostOptions;

static int valid_address(const char *a)
{
	if (!a || !*a || strlen(a) >= LOBBY_FIELD_MAX)
		return 0;
	for (; *a; ++a)
		if (!((*a >= 'a' && *a <= 'z') || (*a >= 'A' && *a <= 'Z') || (*a >= '0' && *a <= '9') || *a == '.' || *a == '-' || *a == ':'))
			return 0;
	return 1;
}

/* Builds the -coop* arguments for the DLL (crygame/coop/CoopArgs.h). Returns 0 with a reason on bad input. */
static int host_args(const HostOptions *o, char *out, size_t size, char *why, size_t whySize)
{
	char part[256];
	if (!lobby_valid_ident(o->map)) { _snprintf(why, whySize, "choose a map (letters, digits, _ and - only)"); return 0; }
	if (o->mission[0] && !lobby_valid_ident(o->mission)) { _snprintf(why, whySize, "mission name may only use letters, digits, _ and -"); return 0; }
	if (!lobby_valid_text(o->name)) { _snprintf(why, whySize, "server name may only use letters, digits, spaces and _ - . ! #"); return 0; }
	if (o->maxPlayers < 2 || o->maxPlayers > 16) { _snprintf(why, whySize, "max players must be 2..16"); return 0; }
	if (o->port < 1024 || o->port > 65535) { _snprintf(why, whySize, "port must be 1024..65535"); return 0; }
	if (o->password[0] && !lobby_valid_ident(o->password)) { _snprintf(why, whySize, "password may only use letters, digits, _ and -"); return 0; }
	_snprintf(out, size, "-coophost:%s%s%s -coopmax:%d -coopport:%d \"-coopname:%s\"", o->map,
		o->mission[0] ? ":" : "", o->mission, o->maxPlayers, o->port, o->name);
	if (o->password[0])
	{
		_snprintf(part, sizeof(part), " -cooppass:%s", o->password);
		strncat(out, part, size - strlen(out) - 1);
	}
	out[size - 1] = 0;
	return 1;
}

static void describe_game(const GameInfo *g)
{
	if (g->hasVersion)
		log_msg("Far Cry: %s, FarCry.exe version %u.%u.%u.%u%s", g->root, g->ver[0], g->ver[1], g->ver[2], g->ver[3],
			g->is14 ? "" : "  <-- FarCryCoop needs patch 1.4");
	else
		log_msg("Far Cry: %s, FarCry.exe has no version resource (cannot confirm patch 1.4)", g->root);
	if (g->modInstalled)
		log_msg("mod: installed in %s (build %s)", g->modDir, g->modBuild);
	else
		log_msg("mod: not installed yet");
}

static LobbySession session_for(const GameInfo *g, const HostOptions *o)
{
	LobbySession s;
	memset(&s, 0, sizeof(s));
	strcpy(s.name, o->name);
	strcpy(s.map, o->map);
	strcpy(s.mission, o->mission);
	strcpy(s.build, g->modBuild[0] ? g->modBuild : "unknown");
	s.port = o->port;
	s.maxPlayers = o->maxPlayers;
	s.hasPassword = o->password[0] != 0;
	return s;
}

/* ------------------------------------------------------------------------------------------------------ */
/* command line                                                                                            */

static const char *arg_value(int argc, char **argv, int *i)
{
	if (*i + 1 >= argc)
	{
		log_msg("ERROR: %s needs a value", argv[*i]);
		exit(2);
	}
	return argv[++*i];
}

static int run_cli(int argc, char **argv)
{
	const char *gameDir = NULL, *join = NULL, *target = NULL, *build = NULL;
	int check = 0, install = 0, dry = 0, ignoreVersion = 0, noLobby = 0, discover = 0, timeout = 1500, serve = -1, i;
	HostOptions o;
	GameInfo g;
	char root[FCC_PATH], why[512];
	int haveHost = 0;

	memset(&o, 0, sizeof(o));
	strcpy(o.name, "FarCryCoop");
	o.maxPlayers = 4;
	o.port = DEFAULT_GAME_PORT;

	for (i = 1; i < argc; ++i)
	{
		const char *a = argv[i];
		if (!strcmp(a, "--game")) gameDir = arg_value(argc, argv, &i);
		else if (!strcmp(a, "--check")) check = 1;
		else if (!strcmp(a, "--install")) install = 1;
		else if (!strcmp(a, "--host")) { strncpy(o.map, arg_value(argc, argv, &i), LOBBY_FIELD_MAX - 1); haveHost = 1; }
		else if (!strcmp(a, "--map")) strncpy(o.map, arg_value(argc, argv, &i), LOBBY_FIELD_MAX - 1);
		else if (!strcmp(a, "--mission")) strncpy(o.mission, arg_value(argc, argv, &i), LOBBY_FIELD_MAX - 1);
		else if (!strcmp(a, "--name")) strncpy(o.name, arg_value(argc, argv, &i), LOBBY_FIELD_MAX - 1);
		else if (!strcmp(a, "--password")) strncpy(o.password, arg_value(argc, argv, &i), LOBBY_FIELD_MAX - 1);
		else if (!strcmp(a, "--max")) o.maxPlayers = atoi(arg_value(argc, argv, &i));
		else if (!strcmp(a, "--port")) o.port = atoi(arg_value(argc, argv, &i));
		else if (!strcmp(a, "--join")) join = arg_value(argc, argv, &i);
		else if (!strcmp(a, "--dry-run")) dry = 1;
		else if (!strcmp(a, "--ignore-version")) ignoreVersion = 1;
		else if (!strcmp(a, "--no-lobby")) noLobby = 1;
		else if (!strcmp(a, "--discover")) discover = 1;
		else if (!strcmp(a, "--timeout")) timeout = atoi(arg_value(argc, argv, &i));
		else if (!strcmp(a, "--target")) target = arg_value(argc, argv, &i);
		else if (!strcmp(a, "--lobby-serve")) serve = atoi(arg_value(argc, argv, &i));
		else if (!strcmp(a, "--build")) build = arg_value(argc, argv, &i);
		else if (!strcmp(a, "--version")) { log_msg("FarCryCoop launcher %s", FCC_VERSION); return 0; }
		else if (!strcmp(a, "--help") || !strcmp(a, "/?"))
		{
			log_msg("FarCryCoop launcher %s. Run without arguments for the window. Options: --game DIR --check "
				"--install --host MAP [--mission M --max N --port P --name S --password X] --join ADDRESS --dry-run "
				"--ignore-version --no-lobby --discover [--timeout MS --target IP] --lobby-serve SEC", FCC_VERSION);
			return 0;
		}
		else { log_msg("ERROR: unknown option %s (try --help)", a); return 2; }
	}
	if (haveHost && join) { log_msg("ERROR: --host and --join are exclusive"); return 2; }

	if (discover || serve >= 0)
	{
		if (!lobby_net_init())
			return 1;
		if (discover)
		{
			LobbyFound found[32];
			int n = lobby_discover(timeout, target, LOBBY_PORT, found, 32), k;
			if (n < 0)
				return 1;
			log_msg("discover: %d session(s)", n);
			for (k = 0; k < n; ++k)
				log_msg("SESSION %s name=%s map=%s mission=%s port=%d max=%d build=%s pw=%d ping=%d",
					found[k].address, found[k].session.name, found[k].session.map, found[k].session.mission,
					found[k].session.port, found[k].session.maxPlayers, found[k].session.build,
					found[k].session.hasPassword, found[k].pingMs);
			return n > 0 ? 0 : 3;
		}
		else
		{
			LobbySession s;
			LobbyHost *h;
			memset(&s, 0, sizeof(s));
			strcpy(s.name, o.name);
			strcpy(s.map, o.map[0] ? o.map : "test");
			strcpy(s.mission, o.mission);
			strcpy(s.build, build ? build : "test");
			s.port = o.port;
			s.maxPlayers = o.maxPlayers;
			s.hasPassword = o.password[0] != 0;
			h = lobby_host_start(&s, LOBBY_PORT, why, sizeof(why));
			if (!h) { log_msg("ERROR: %s", why); return 1; }
			Sleep((DWORD)serve * 1000);
			i = lobby_host_answers(h);
			lobby_host_stop(h);
			return i > 0 ? 0 : 3;
		}
	}

	if (gameDir)
	{
		if (!game_probe(gameDir, &g))
		{
			log_msg("ERROR: %s is not a Far Cry folder (no Bin32\\FarCry.exe)", gameDir);
			return 1;
		}
	}
	else
	{
		if (!game_autodetect(root, sizeof(root)))
			return 1;
		game_probe(root, &g);
	}
	describe_game(&g);

	if (install)
	{
		if (!mod_install(&g, why, sizeof(why)))
		{
			log_msg("ERROR: install failed: %s", why);
			return 1;
		}
		game_save_setting(g.root);
	}

	if (check)
	{
		char levels[MAX_LEVELS][LEVEL_NAME_MAX], src[FCC_PATH], srcBuild[17] = "";
		int n = game_list_levels(&g, levels, MAX_LEVELS), k;
		log_msg("levels: %d", n);
		for (k = 0; k < n; ++k)
			log_msg("LEVEL %s", levels[k]);
		if (mod_source_dir(src, sizeof(src)))
		{
			mod_build_id(src, srcBuild);
			log_msg("package: %s (build %s)%s", src, srcBuild,
				g.modInstalled && strcmp(srcBuild, g.modBuild) ? "  <-- differs from the installed copy, run --install" : "");
		}
		else
			log_msg("package: mod files not found next to the launcher (%s)", src);
		return (g.hasVersion && g.is14 && g.modInstalled) ? 0 : 4;
	}

	if (haveHost || join)
	{
		char args[512];
		HANDLE proc = NULL;
		if (!g.is14 && !ignoreVersion)
		{
			log_msg("ERROR: FarCryCoop needs Far Cry patch 1.4 (its CryGame.dll is built against 1.4); use --ignore-version to try anyway");
			return 1;
		}
		if (!g.modInstalled)
		{
			log_msg("ERROR: the mod is not installed in %s; run with --install first", g.root);
			return 1;
		}
		if (haveHost)
		{
			if (!host_args(&o, args, sizeof(args), why, sizeof(why))) { log_msg("ERROR: %s", why); return 2; }
		}
		else
		{
			if (!valid_address(join)) { log_msg("ERROR: invalid address '%s'", join); return 2; }
			_snprintf(args, sizeof(args), "-coopjoin:%s", join);
		}
		if (!game_launch(&g, args, dry, &proc, NULL))
			return 1;
		if (dry)
			return 0;
		if (haveHost && !noLobby && lobby_net_init())
		{
			LobbySession s = session_for(&g, &o);
			LobbyHost *h = lobby_host_start(&s, LOBBY_PORT, why, sizeof(why));
			if (!h)
				log_msg("WARNING: LAN discovery disabled: %s (players can still join by address)", why);
			WaitForSingleObject(proc, INFINITE);
			lobby_host_stop(h);
		}
		else
			WaitForSingleObject(proc, INFINITE);
		{
			DWORD code = 0;
			GetExitCodeProcess(proc, &code);
			log_msg("Far Cry exited (code %lu); see %s\\log.txt", code, g.root);
		}
		CloseHandle(proc);
		return 0;
	}
	return 0;
}

/* ------------------------------------------------------------------------------------------------------ */
/* window                                                                                                  */

enum
{
	IDC_GAMEDIR = 100, IDC_BROWSE, IDC_STATUS, IDC_INSTALL, IDC_MAP, IDC_MISSION, IDC_NAME, IDC_MAX, IDC_PORT,
	IDC_PASS, IDC_HOST, IDC_ADDR, IDC_FIND, IDC_FOUND, IDC_JOIN, IDC_LOG, IDC_OPENLOG, IDC_OPENGAMELOG
};
#define WM_APP_LOG (WM_APP + 1)
#define TIMER_GAME 1

static HWND g_wnd;
static GameInfo g_game;
static int g_haveGame;
static HANDLE g_proc;
static LobbyHost *g_lobby;
static LobbyFound g_found[32];
static int g_foundCount;

static void gui_log_sink(const char *line)
{
	size_t n = strlen(line) + 1;
	char *copy = (char *)malloc(n);
	if (!copy)
		return;
	memcpy(copy, line, n);
	if (!g_wnd || !PostMessageA(g_wnd, WM_APP_LOG, 0, (LPARAM)copy))
		free(copy);
}

static HWND add(HWND parent, const char *cls, const char *text, DWORD style, int x, int y, int w, int h, int id)
{
	HWND c = CreateWindowExA(!strcmp(cls, "EDIT") || !strcmp(cls, "LISTBOX") ? WS_EX_CLIENTEDGE : 0, cls, text,
		WS_CHILD | WS_VISIBLE | style, x, y, w, h, parent, (HMENU)(INT_PTR)id, GetModuleHandleA(NULL), NULL);
	SendMessageA(c, WM_SETFONT, (WPARAM)GetStockObject(DEFAULT_GUI_FONT), TRUE);
	return c;
}

static void get_text(int id, char *out, int size)
{
	GetDlgItemTextA(g_wnd, id, out, size);
}

static void set_busy(int busy)
{
	EnableWindow(GetDlgItem(g_wnd, IDC_HOST), !busy && g_haveGame);
	EnableWindow(GetDlgItem(g_wnd, IDC_JOIN), !busy && g_haveGame);
	EnableWindow(GetDlgItem(g_wnd, IDC_INSTALL), !busy && g_haveGame);
}

static void refresh_game(const char *root)
{
	char status[512], levels[MAX_LEVELS][LEVEL_NAME_MAX];
	int n, k;
	HWND map = GetDlgItem(g_wnd, IDC_MAP);

	g_haveGame = root && *root && game_probe(root, &g_game);
	SendMessageA(map, CB_RESETCONTENT, 0, 0);
	if (!g_haveGame)
	{
		SetDlgItemTextA(g_wnd, IDC_STATUS, "Far Cry not found here: choose the folder that contains Bin32\\FarCry.exe.");
		set_busy(0);
		return;
	}
	SetDlgItemTextA(g_wnd, IDC_GAMEDIR, g_game.root);
	game_save_setting(g_game.root);
	describe_game(&g_game);
	if (g_game.hasVersion)
		_snprintf(status, sizeof(status), "FarCry.exe %u.%u.%u.%u%s   |   mod: %s%s", g_game.ver[0], g_game.ver[1],
			g_game.ver[2], g_game.ver[3], g_game.is14 ? " (patch 1.4)" : " (NOT 1.4: patch first)",
			g_game.modInstalled ? "installed, build " : "not installed", g_game.modInstalled ? g_game.modBuild : "");
	else
		_snprintf(status, sizeof(status), "FarCry.exe found (no version info)   |   mod: %s%s",
			g_game.modInstalled ? "installed, build " : "not installed", g_game.modInstalled ? g_game.modBuild : "");
	status[sizeof(status) - 1] = 0;
	SetDlgItemTextA(g_wnd, IDC_STATUS, status);
	n = game_list_levels(&g_game, levels, MAX_LEVELS);
	for (k = 0; k < n; ++k)
		SendMessageA(map, CB_ADDSTRING, 0, (LPARAM)levels[k]);
	if (n)
		SendMessageA(map, CB_SETCURSEL, 0, 0);
	log_msg("%d level folders found", n);
	set_busy(g_proc != NULL);
}

static int confirm_version(void)
{
	if (g_game.is14)
		return 1;
	return MessageBoxA(g_wnd, "FarCry.exe does not report version 1.4.\n\nFarCryCoop's CryGame.dll is built from the "
		"1.4 SDK and needs the 1.4 patch. Start anyway?", "FarCryCoop", MB_YESNO | MB_ICONWARNING) == IDYES;
}

static int ensure_installed(void)
{
	char why[512];
	if (g_game.modInstalled)
	{
		char src[FCC_PATH], srcBuild[17];
		if (mod_source_dir(src, sizeof(src)) && mod_build_id(src, srcBuild) && strcmp(srcBuild, g_game.modBuild) != 0)
			log_msg("note: the installed mod (build %s) differs from this package (build %s); click Install / update to sync",
				g_game.modBuild, srcBuild);
		return 1;
	}
	if (MessageBoxA(g_wnd, "Install the FarCryCoop mod into <Far Cry>\\Mods\\FarCryCoop now?\n\n"
			"No original game file is changed. An existing FarCryCoop folder is backed up first.", "FarCryCoop",
			MB_YESNO | MB_ICONQUESTION) != IDYES)
		return 0;
	if (!mod_install(&g_game, why, sizeof(why)))
	{
		log_msg("ERROR: install failed: %s", why);
		MessageBoxA(g_wnd, why, "FarCryCoop: install failed", MB_OK | MB_ICONERROR);
		return 0;
	}
	refresh_game(g_game.root);
	return 1;
}

static void on_host(void)
{
	HostOptions o;
	char args[512], why[256], buf[32];
	LobbySession s;
	memset(&o, 0, sizeof(o));
	get_text(IDC_MAP, o.map, sizeof(o.map));
	get_text(IDC_MISSION, o.mission, sizeof(o.mission));
	get_text(IDC_NAME, o.name, sizeof(o.name));
	get_text(IDC_PASS, o.password, sizeof(o.password));
	get_text(IDC_MAX, buf, sizeof(buf)); o.maxPlayers = atoi(buf);
	get_text(IDC_PORT, buf, sizeof(buf)); o.port = atoi(buf);
	if (!host_args(&o, args, sizeof(args), why, sizeof(why)))
	{
		MessageBoxA(g_wnd, why, "FarCryCoop", MB_OK | MB_ICONWARNING);
		return;
	}
	if (!confirm_version() || !ensure_installed())
		return;
	if (!game_launch(&g_game, args, 0, &g_proc, NULL))
	{
		MessageBoxA(g_wnd, "Far Cry could not be started; see the launcher log.", "FarCryCoop", MB_OK | MB_ICONERROR);
		return;
	}
	s = session_for(&g_game, &o);
	g_lobby = lobby_host_start(&s, LOBBY_PORT, why, sizeof(why));
	if (!g_lobby)
		log_msg("WARNING: LAN discovery disabled: %s (players can still join by address)", why);
	log_msg("Far Cry is starting as co-op host on %s. Players can join once the level has loaded "
		"(UDP %d must be reachable). [coop] lines in log.txt show the session state.", o.map, o.port);
	set_busy(1);
}

static void on_join(void)
{
	char addr[LOBBY_FIELD_MAX], args[128];
	int sel = (int)SendDlgItemMessageA(g_wnd, IDC_FOUND, LB_GETCURSEL, 0, 0);
	get_text(IDC_ADDR, addr, sizeof(addr));
	if (!valid_address(addr))
	{
		MessageBoxA(g_wnd, "Enter the host's IP address (or pick a LAN game).", "FarCryCoop", MB_OK | MB_ICONWARNING);
		return;
	}
	if (!confirm_version() || !ensure_installed())
		return;
	if (sel >= 0 && sel < g_foundCount && !strncmp(g_found[sel].address, addr, strlen(g_found[sel].address))
		&& strcmp(g_found[sel].session.build, g_game.modBuild) != 0)
	{
		char msg[256];
		_snprintf(msg, sizeof(msg), "The host runs FarCryCoop build %s, you have %s. Mismatched builds can desync "
			"or fail to connect. Join anyway?", g_found[sel].session.build, g_game.modBuild);
		msg[sizeof(msg) - 1] = 0;
		if (MessageBoxA(g_wnd, msg, "FarCryCoop", MB_YESNO | MB_ICONWARNING) != IDYES)
			return;
	}
	_snprintf(args, sizeof(args), "-coopjoin:%s", addr);
	args[sizeof(args) - 1] = 0;
	if (!game_launch(&g_game, args, 0, &g_proc, NULL))
	{
		MessageBoxA(g_wnd, "Far Cry could not be started; see the launcher log.", "FarCryCoop", MB_OK | MB_ICONERROR);
		return;
	}
	log_msg("Far Cry is starting and will try to connect to %s. Whether the connection worked is shown in "
		"the game and in log.txt; this launcher cannot see it.", addr);
	set_busy(1);
}

static void on_find(void)
{
	int k;
	char line[256], target[LOBBY_FIELD_MAX];
	HCURSOR old = SetCursor(LoadCursor(NULL, IDC_WAIT));
	get_text(IDC_ADDR, target, sizeof(target));
	SendDlgItemMessageA(g_wnd, IDC_FOUND, LB_RESETCONTENT, 0, 0);
	g_foundCount = lobby_discover(1500, valid_address(target) ? target : NULL, LOBBY_PORT, g_found, 32);
	if (g_foundCount < 0)
		g_foundCount = 0;
	for (k = 0; k < g_foundCount; ++k)
	{
		_snprintf(line, sizeof(line), "%s  -  %s  (%s%s%s)  %s  build %s%s", g_found[k].address, g_found[k].session.name,
			g_found[k].session.map, g_found[k].session.mission[0] ? ":" : "", g_found[k].session.mission,
			g_found[k].session.hasPassword ? "password" : "open", g_found[k].session.build,
			strcmp(g_found[k].session.build, g_game.modBuild) ? " (differs from yours)" : "");
		line[sizeof(line) - 1] = 0;
		SendDlgItemMessageA(g_wnd, IDC_FOUND, LB_ADDSTRING, 0, (LPARAM)line);
	}
	if (!g_foundCount)
		log_msg("no co-op hosts answered on the LAN (the host must have started from FarCryCoop.exe; firewalls "
			"can block UDP %d). You can still type the host's IP.", LOBBY_PORT);
	SetCursor(old);
}

static void on_browse(void)
{
	BROWSEINFOA bi;
	char path[MAX_PATH];
	LPITEMIDLIST pidl;
	memset(&bi, 0, sizeof(bi));
	bi.hwndOwner = g_wnd;
	bi.lpszTitle = "Choose the Far Cry folder (the one that contains Bin32\\FarCry.exe)";
	bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
	pidl = SHBrowseForFolderA(&bi);
	if (!pidl)
		return;
	if (SHGetPathFromIDListA(pidl, path))
		refresh_game(path);
	CoTaskMemFree(pidl);
}

static void open_file(const char *path)
{
	if (!file_exists(path))
	{
		log_msg("%s does not exist yet", path);
		return;
	}
	ShellExecuteA(g_wnd, "open", path, NULL, NULL, SW_SHOWNORMAL);
}

static LRESULT CALLBACK wnd_proc(HWND w, UINT msg, WPARAM wp, LPARAM lp)
{
	switch (msg)
	{
	case WM_CREATE:
		g_wnd = w;
		add(w, "STATIC", "Far Cry folder:", 0, 12, 14, 90, 18, -1);
		add(w, "EDIT", "", ES_AUTOHSCROLL | ES_READONLY, 104, 10, 420, 22, IDC_GAMEDIR);
		add(w, "BUTTON", "Browse...", BS_PUSHBUTTON, 530, 10, 90, 24, IDC_BROWSE);
		add(w, "STATIC", "", 0, 12, 40, 520, 18, IDC_STATUS);
		add(w, "BUTTON", "Install / update mod", BS_PUSHBUTTON, 490, 36, 130, 24, IDC_INSTALL);

		add(w, "BUTTON", "Host a co-op game", BS_GROUPBOX, 8, 66, 616, 104, -1);
		add(w, "STATIC", "Map:", 0, 20, 90, 70, 18, -1);
		add(w, "COMBOBOX", "", CBS_DROPDOWNLIST | WS_VSCROLL, 96, 86, 170, 300, IDC_MAP);
		add(w, "STATIC", "Mission (optional):", 0, 280, 90, 110, 18, -1);
		add(w, "EDIT", "", ES_AUTOHSCROLL, 392, 86, 120, 22, IDC_MISSION);
		add(w, "STATIC", "Server name:", 0, 20, 118, 74, 18, -1);
		add(w, "EDIT", "FarCryCoop", ES_AUTOHSCROLL, 96, 114, 170, 22, IDC_NAME);
		add(w, "STATIC", "Players:", 0, 280, 118, 50, 18, -1);
		add(w, "EDIT", "4", ES_NUMBER, 332, 114, 36, 22, IDC_MAX);
		add(w, "STATIC", "Port:", 0, 380, 118, 30, 18, -1);
		add(w, "EDIT", "49001", ES_NUMBER, 412, 114, 60, 22, IDC_PORT);
		add(w, "STATIC", "Password:", 0, 20, 146, 70, 18, -1);
		add(w, "EDIT", "", ES_AUTOHSCROLL | ES_PASSWORD, 96, 142, 170, 22, IDC_PASS);
		add(w, "BUTTON", "Host", BS_DEFPUSHBUTTON, 520, 138, 96, 26, IDC_HOST);

		add(w, "BUTTON", "Join a co-op game", BS_GROUPBOX, 8, 176, 616, 150, -1);
		add(w, "STATIC", "Host address:", 0, 20, 200, 70, 18, -1);
		add(w, "EDIT", "", ES_AUTOHSCROLL, 94, 196, 170, 22, IDC_ADDR);
		add(w, "BUTTON", "Find LAN games", BS_PUSHBUTTON, 274, 194, 110, 26, IDC_FIND);
		add(w, "BUTTON", "Join", BS_PUSHBUTTON, 520, 194, 96, 26, IDC_JOIN);
		add(w, "LISTBOX", "", LBS_NOTIFY | WS_VSCROLL, 20, 226, 596, 92, IDC_FOUND);

		add(w, "EDIT", "", ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL | WS_VSCROLL, 8, 334, 616, 150, IDC_LOG);
		add(w, "BUTTON", "Open game log.txt", BS_PUSHBUTTON, 8, 490, 130, 24, IDC_OPENGAMELOG);
		add(w, "BUTTON", "Open launcher log", BS_PUSHBUTTON, 146, 490, 130, 24, IDC_OPENLOG);
		add(w, "STATIC", "Unofficial mod. Not affiliated with Crytek or Ubisoft.", 0, 300, 494, 324, 18, -1);
		SetTimer(w, TIMER_GAME, 1000, NULL);
		return 0;

	case WM_APP_LOG:
	{
		char *line = (char *)lp;
		HWND log = GetDlgItem(w, IDC_LOG);
		int len = GetWindowTextLengthA(log);
		if (len > 60000)
		{
			SendMessageA(log, EM_SETSEL, 0, len / 2);
			SendMessageA(log, EM_REPLACESEL, FALSE, (LPARAM)"");
			len = GetWindowTextLengthA(log);
		}
		SendMessageA(log, EM_SETSEL, len, len);
		SendMessageA(log, EM_REPLACESEL, FALSE, (LPARAM)line);
		SendMessageA(log, EM_REPLACESEL, FALSE, (LPARAM)"\r\n");
		free(line);
		return 0;
	}

	case WM_TIMER:
		if (wp == TIMER_GAME && g_proc && WaitForSingleObject(g_proc, 0) == WAIT_OBJECT_0)
		{
			DWORD code = 0;
			GetExitCodeProcess(g_proc, &code);
			CloseHandle(g_proc);
			g_proc = NULL;
			lobby_host_stop(g_lobby);
			g_lobby = NULL;
			log_msg("Far Cry exited (code %lu). If something went wrong, open the game log.", code);
			set_busy(0);
		}
		return 0;

	case WM_COMMAND:
		switch (LOWORD(wp))
		{
		case IDC_BROWSE: on_browse(); break;
		case IDC_INSTALL:
			if (g_haveGame)
			{
				char why[512];
				if (mod_install(&g_game, why, sizeof(why)))
					refresh_game(g_game.root);
				else
					MessageBoxA(w, why, "FarCryCoop: install failed", MB_OK | MB_ICONERROR);
			}
			break;
		case IDC_HOST: on_host(); break;
		case IDC_FIND: on_find(); break;
		case IDC_JOIN: on_join(); break;
		case IDC_FOUND:
			if (HIWORD(wp) == LBN_SELCHANGE)
			{
				int sel = (int)SendDlgItemMessageA(w, IDC_FOUND, LB_GETCURSEL, 0, 0);
				if (sel >= 0 && sel < g_foundCount)
				{
					char a[LOBBY_FIELD_MAX];
					if (g_found[sel].session.port == DEFAULT_GAME_PORT)
						strcpy(a, g_found[sel].address);
					else
					{
						/* Far Cry's own "ip:port" handling lives in the closed CryNetwork.dll: untested. */
						_snprintf(a, sizeof(a), "%s:%d", g_found[sel].address, g_found[sel].session.port);
						a[sizeof(a) - 1] = 0;
						log_msg("note: this host uses port %d; joining with %s has not been tested", g_found[sel].session.port, a);
					}
					SetDlgItemTextA(w, IDC_ADDR, a);
				}
			}
			break;
		case IDC_OPENLOG: open_file(log_path()); break;
		case IDC_OPENGAMELOG:
			if (g_haveGame)
			{
				char p[FCC_PATH];
				path_join(p, sizeof(p), g_game.root, "log.txt");
				open_file(p);
			}
			break;
		}
		return 0;

	case WM_CLOSE:
		if (g_proc && MessageBoxA(w, "Far Cry is still running. Close the launcher anyway? (LAN discovery stops; the "
				"game keeps running.)", "FarCryCoop", MB_YESNO | MB_ICONQUESTION) != IDYES)
			return 0;
		DestroyWindow(w);
		return 0;

	case WM_DESTROY:
		lobby_host_stop(g_lobby);
		g_lobby = NULL;
		log_set_sink(NULL);
		PostQuitMessage(0);
		return 0;
	}
	return DefWindowProcA(w, msg, wp, lp);
}

static int run_gui(HINSTANCE inst)
{
	WNDCLASSA wc;
	MSG m;
	char root[FCC_PATH];
	HWND w;

	CoInitialize(NULL);
	lobby_net_init();
	memset(&wc, 0, sizeof(wc));
	wc.lpfnWndProc = wnd_proc;
	wc.hInstance = inst;
	wc.hCursor = LoadCursor(NULL, IDC_ARROW);
	wc.hIcon = LoadIcon(inst, MAKEINTRESOURCE(1));
	wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
	wc.lpszClassName = "FarCryCoopLauncher";
	RegisterClassA(&wc);
	w = CreateWindowExA(0, wc.lpszClassName, "FarCryCoop " FCC_VERSION " - co-op launcher for Far Cry (patch 1.4)",
		WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, CW_USEDEFAULT, CW_USEDEFAULT, 640, 556,
		NULL, NULL, inst, NULL);
	log_set_sink(gui_log_sink);
	log_msg("FarCryCoop launcher %s (log: %s)", FCC_VERSION, log_path());
	ShowWindow(w, SW_SHOW);
	if (game_autodetect(root, sizeof(root)))
		refresh_game(root);
	else
		refresh_game(NULL);
	while (GetMessageA(&m, NULL, 0, 0) > 0)
	{
		if (!IsDialogMessageA(w, &m))
		{
			TranslateMessage(&m);
			DispatchMessageA(&m);
		}
	}
	return 0;
}

int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show)
{
	(void)prev; (void)cmd; (void)show;
	log_init();
	srand(GetTickCount());
	if (__argc > 1)
	{
		/* Print to the console that started us, if any. */
		if (AttachConsole(ATTACH_PARENT_PROCESS))
		{
			freopen("CONOUT$", "w", stdout);
			freopen("CONOUT$", "w", stderr);
		}
		return run_cli(__argc, __argv);
	}
	return run_gui(inst);
}
