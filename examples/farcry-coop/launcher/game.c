/* FarCryCoop launcher: the user's Far Cry install, the mod folder inside it, and starting the game. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "game.h"
#include "lobby_proto.h"

static void probe_version(GameInfo *g)
{
	DWORD dummy, size = GetFileVersionInfoSizeA(g->exe, &dummy);
	void *data;
	VS_FIXEDFILEINFO *ffi;
	UINT len;

	g->hasVersion = 0;
	if (!size)
		return;
	data = malloc(size);
	if (data && GetFileVersionInfoA(g->exe, 0, size, data) && VerQueryValueA(data, "\\", (void **)&ffi, &len) && ffi)
	{
		g->ver[0] = HIWORD(ffi->dwFileVersionMS);
		g->ver[1] = LOWORD(ffi->dwFileVersionMS);
		g->ver[2] = HIWORD(ffi->dwFileVersionLS);
		g->ver[3] = LOWORD(ffi->dwFileVersionLS);
		g->hasVersion = 1;
		g->is14 = g->ver[0] == 1 && g->ver[1] == 4;
	}
	free(data);
}

int game_probe(const char *root, GameInfo *g)
{
	char dll[FCC_PATH];
	memset(g, 0, sizeof(*g));
	if (!root || !*root || strlen(root) >= FCC_PATH - 64)
		return 0;
	strcpy(g->root, root);
	{
		size_t n = strlen(g->root);
		while (n > 3 && (g->root[n - 1] == '\\' || g->root[n - 1] == '/'))
			g->root[--n] = 0;
	}
	path_join(g->exe, sizeof(g->exe), g->root, "Bin32\\FarCry.exe");
	if (!file_exists(g->exe))
		return 0;
	probe_version(g);
	path_join(g->modDir, sizeof(g->modDir), g->root, "Mods\\" MOD_NAME);
	path_join(dll, sizeof(dll), g->modDir, "Bin32\\CryGame.dll");
	g->modInstalled = file_exists(dll);
	if (g->modInstalled)
		mod_build_id(g->modDir, g->modBuild);
	return 1;
}

static int try_candidate(const char *root, char *out, size_t size, const char *source)
{
	GameInfo g;
	if (!game_probe(root, &g))
		return 0;
	log_msg("found Far Cry at %s (%s)", g.root, source);
	strncpy(out, g.root, size - 1);
	out[size - 1] = 0;
	return 1;
}

static int reg_string(HKEY hive, const char *key, const char *value, char *out, DWORD size)
{
	HKEY h;
	DWORD type, n = size;
	LONG r;
	if (RegOpenKeyExA(hive, key, 0, KEY_READ | KEY_WOW64_32KEY, &h) != ERROR_SUCCESS)
		return 0;
	r = RegQueryValueExA(h, value, NULL, &type, (BYTE *)out, &n);
	RegCloseKey(h);
	if (r != ERROR_SUCCESS || (type != REG_SZ && type != REG_EXPAND_SZ) || n == 0)
		return 0;
	out[size - 1] = 0;
	return 1;
}

/* Steam: <SteamPath>\steamapps\common\Far Cry, plus the "path" entries of libraryfolders.vdf. */
static int try_steam(char *out, size_t size)
{
	char steam[FCC_PATH], vdf[FCC_PATH], cand[FCC_PATH], line[FCC_PATH];
	FILE *f;
	if (!reg_string(HKEY_CURRENT_USER, "Software\\Valve\\Steam", "SteamPath", steam, sizeof(steam)))
		return 0;
	path_join(cand, sizeof(cand), steam, "steamapps\\common\\Far Cry");
	if (try_candidate(cand, out, size, "Steam"))
		return 1;
	path_join(vdf, sizeof(vdf), steam, "steamapps\\libraryfolders.vdf");
	f = fopen(vdf, "r");
	if (!f)
		return 0;
	while (fgets(line, sizeof(line), f))
	{
		char *k = strstr(line, "\"path\""), *a, *b, lib[FCC_PATH];
		size_t i, j = 0;
		if (!k)
			continue;
		a = strchr(k + 6, '"');
		b = a ? strrchr(a + 1, '"') : NULL;
		if (!a || !b || b <= a + 1)
			continue;
		for (i = (size_t)(a + 1 - line); i < (size_t)(b - line) && j < sizeof(lib) - 1; ++i)
		{
			if (line[i] == '\\' && line[i + 1] == '\\')
				++i;
			lib[j++] = line[i];
		}
		lib[j] = 0;
		path_join(cand, sizeof(cand), lib, "steamapps\\common\\Far Cry");
		if (try_candidate(cand, out, size, "Steam library"))
		{
			fclose(f);
			return 1;
		}
	}
	fclose(f);
	return 0;
}

/* GOG and other installers: subkeys whose name/DisplayName says "Far Cry" (not a sequel) with a path. */
static int try_registry_list(HKEY hive, const char *base, const char *nameValue, const char *pathValue,
	char *out, size_t size, const char *source)
{
	HKEY h;
	DWORD i;
	char sub[256];
	if (RegOpenKeyExA(hive, base, 0, KEY_READ | KEY_WOW64_32KEY, &h) != ERROR_SUCCESS)
		return 0;
	for (i = 0; ; ++i)
	{
		DWORD n = sizeof(sub);
		char key[512], name[256], path[FCC_PATH];
		if (RegEnumKeyExA(h, i, sub, &n, NULL, NULL, NULL, NULL) != ERROR_SUCCESS)
			break;
		_snprintf(key, sizeof(key), "%s\\%s", base, sub);
		key[sizeof(key) - 1] = 0;
		if (!reg_string(hive, key, nameValue, name, sizeof(name)))
			continue;
		if (_stricmp(name, "Far Cry") != 0 && _strnicmp(name, "Far Cry (", 9) != 0 && _stricmp(name, "FarCry") != 0)
			continue;
		if (reg_string(hive, key, pathValue, path, sizeof(path)) && try_candidate(path, out, size, source))
		{
			RegCloseKey(h);
			return 1;
		}
	}
	RegCloseKey(h);
	return 0;
}

static void settings_path(char *out, size_t size)
{
	path_join(out, size, exe_dir(), "FarCryCoop.ini");
}

void game_save_setting(const char *root)
{
	char ini[FCC_PATH];
	settings_path(ini, sizeof(ini));
	WritePrivateProfileStringA("launcher", "game", root, ini);
}

int game_autodetect(char *root, size_t size)
{
	char cand[FCC_PATH], ini[FCC_PATH];
	const char *dir = exe_dir();

	settings_path(ini, sizeof(ini));
	GetPrivateProfileStringA("launcher", "game", "", cand, sizeof(cand), ini);
	if (cand[0] && try_candidate(cand, root, size, "FarCryCoop.ini"))
		return 1;
	if (try_candidate(dir, root, size, "launcher folder"))
		return 1;
	strcpy(cand, dir);
	if (strrchr(cand, '\\'))
	{
		*strrchr(cand, '\\') = 0;
		if (try_candidate(cand, root, size, "parent of launcher folder"))
			return 1;
	}
	if (try_steam(root, size))
		return 1;
	if (try_registry_list(HKEY_LOCAL_MACHINE, "SOFTWARE\\GOG.com\\Games", "gameName", "path", root, size, "GOG"))
		return 1;
	if (try_registry_list(HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
			"DisplayName", "InstallLocation", root, size, "Windows uninstall list"))
		return 1;
	log_msg("no Far Cry install found automatically; choose the folder that contains Bin32\\FarCry.exe");
	return 0;
}

int game_list_levels(const GameInfo *g, char names[][LEVEL_NAME_MAX], int max)
{
	char pattern[FCC_PATH];
	WIN32_FIND_DATAA fd;
	HANDLE h;
	int n = 0;

	path_join(pattern, sizeof(pattern), g->root, "Levels\\*");
	h = FindFirstFileA(pattern, &fd);
	if (h == INVALID_HANDLE_VALUE)
		return 0;
	do
	{
		char dir[FCC_PATH], probe[FCC_PATH];
		WIN32_FIND_DATAA fd2;
		HANDLE h2;
		int isLevel;
		if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || fd.cFileName[0] == '.')
			continue;
		path_join(dir, sizeof(dir), g->root, "Levels");
		path_join(dir, sizeof(dir), dir, fd.cFileName);
		path_join(probe, sizeof(probe), dir, "LevelData.xml");
		isLevel = file_exists(probe);
		if (!isLevel)
		{
			path_join(probe, sizeof(probe), dir, "*.pak");
			h2 = FindFirstFileA(probe, &fd2);
			isLevel = h2 != INVALID_HANDLE_VALUE;
			if (h2 != INVALID_HANDLE_VALUE)
				FindClose(h2);
		}
		if (!isLevel)
			continue;
		if (!lobby_valid_ident(fd.cFileName))
		{
			log_msg("skipping level folder '%s': only letters, digits, _ and - can be passed to the game", fd.cFileName);
			continue;
		}
		if (n < max)
		{
			memcpy(names[n], fd.cFileName, strlen(fd.cFileName) + 1);   /* lobby_valid_ident: < LEVEL_NAME_MAX */
			++n;
		}
	} while (FindNextFileA(h, &fd));
	FindClose(h);
	return n;
}

/* ---- mod files embedded in FarCryCoop.exe ---------------------------------------------------------------
 * build.sh embeds every mod file as an RCDATA resource and a manifest (RCDATA 99) of "id|relative\\path" lines.
 * Without a mod\\FarCryCoop folder next to the launcher they are extracted to %TEMP%\\FarCryCoop-<hash>. */

#define MANIFEST_ID 99

static int write_resource(int id, const char *path)
{
	HRSRC r = FindResourceA(NULL, MAKEINTRESOURCEA(id), (LPCSTR)RT_RCDATA);
	HGLOBAL g;
	const void *data;
	DWORD len;
	FILE *f;
	size_t written;
	if (!r || !(g = LoadResource(NULL, r)) || !(data = LockResource(g)))
		return 0;
	len = SizeofResource(NULL, r);
	f = fopen(path, "wb");
	if (!f)
		return 0;
	written = fwrite(data, 1, len, f);
	fclose(f);
	return written == len;
}

static int make_dirs(const char *path)
{
	char tmp[FCC_PATH];
	char *p;
	strncpy(tmp, path, sizeof(tmp) - 1);
	tmp[sizeof(tmp) - 1] = 0;
	for (p = tmp + 3; *p; ++p)
	{
		if (*p == '\\')
		{
			*p = 0;
			CreateDirectoryA(tmp, NULL);
			*p = '\\';
		}
	}
	return CreateDirectoryA(tmp, NULL) || GetLastError() == ERROR_ALREADY_EXISTS;
}

int mod_embedded(void)
{
	return FindResourceA(NULL, MAKEINTRESOURCEA(MANIFEST_ID), (LPCSTR)RT_RCDATA) != NULL;
}

static int extract_embedded(char *out, size_t size)
{
	HRSRC r = FindResourceA(NULL, MAKEINTRESOURCEA(MANIFEST_ID), (LPCSTR)RT_RCDATA);
	HGLOBAL g;
	const char *manifest;
	DWORD len, i;
	char *text, *line, *next, tempDir[FCC_PATH], base[FCC_PATH];
	unsigned long h = 2166136261UL;
	int files = 0, ok = 1;

	if (!r || !(g = LoadResource(NULL, r)) || !(manifest = (const char *)LockResource(g)))
		return 0;
	len = SizeofResource(NULL, r);
	text = (char *)malloc(len + 1);
	memcpy(text, manifest, len);
	text[len] = 0;
	for (i = 0; i < len; ++i)
		h = (h ^ (unsigned char)text[i]) * 16777619UL;   /* manifest holds the per-file build ids */

	GetTempPathA(sizeof(tempDir), tempDir);
	_snprintf(base, sizeof(base), "%sFarCryCoop-%08lx\\mod\\" MOD_NAME, tempDir, h);
	base[sizeof(base) - 1] = 0;
	for (line = text; line && *line; line = next)
	{
		char *bar, full[FCC_PATH], dir[FCC_PATH];
		int id;
		next = strchr(line, '\n');
		if (next)
			*next++ = 0;
		if (line[0] == '#' || !(bar = strchr(line, '|')))
			continue;
		*bar = 0;
		id = atoi(line);
		if (strstr(bar + 1, "..") || strchr(bar + 1, ':'))
			continue;
		path_join(full, sizeof(full), base, bar + 1);
		strcpy(dir, full);
		*strrchr(dir, '\\') = 0;
		if (!make_dirs(dir) || !write_resource(id, full))
		{
			log_msg("ERROR: cannot extract embedded file %s", full);
			ok = 0;
			break;
		}
		++files;
	}
	free(text);
	if (!ok || !files)
		return 0;
	_snprintf(out, size, "%s", base);
	out[size - 1] = 0;
	return 1;
}

int mod_source_dir(char *out, size_t size)
{
	static char cached[FCC_PATH];
	path_join(out, size, exe_dir(), "mod\\" MOD_NAME);
	if (dir_exists(out))
		return 1;
	if (!cached[0] && mod_embedded() && !extract_embedded(cached, sizeof(cached)))
		cached[0] = 0;
	if (!cached[0])
		return 0;
	strncpy(out, cached, size - 1);
	out[size - 1] = 0;
	return 1;
}

/* ---- build id ---------------------------------------------------------------------------------------- */

typedef struct FileList
{
	char (*items)[FCC_PATH];
	int count, cap;
} FileList;

static void list_files(const char *root, const char *rel, FileList *fl)
{
	char pattern[FCC_PATH], sub[FCC_PATH];
	WIN32_FIND_DATAA fd;
	HANDLE h;

	path_join(sub, sizeof(sub), root, rel);
	path_join(pattern, sizeof(pattern), rel[0] ? sub : root, "*");
	h = FindFirstFileA(pattern, &fd);
	if (h == INVALID_HANDLE_VALUE)
		return;
	do
	{
		char childRel[FCC_PATH];
		if (!strcmp(fd.cFileName, ".") || !strcmp(fd.cFileName, ".."))
			continue;
		if (rel[0])
			path_join(childRel, sizeof(childRel), rel, fd.cFileName);
		else
			strcpy(childRel, fd.cFileName);
		if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
			list_files(root, childRel, fl);
		else
		{
			if (fl->count == fl->cap)
			{
				fl->cap = fl->cap ? fl->cap * 2 : 64;
				fl->items = realloc(fl->items, (size_t)fl->cap * sizeof(*fl->items));
			}
			strcpy(fl->items[fl->count++], childRel);
		}
	} while (FindNextFileA(h, &fd));
	FindClose(h);
}

static int cmp_path(const void *a, const void *b)
{
	return _stricmp((const char *)a, (const char *)b);
}

int mod_build_id(const char *dir, char hex[17])
{
	FileList fl = { 0, 0, 0 };
	unsigned long long h = 1469598103934665603ULL;
	const unsigned long long prime = 1099511628211ULL;
	int i;

	hex[0] = 0;
	list_files(dir, "", &fl);
	if (!fl.count)
		return 0;
	qsort(fl.items, (size_t)fl.count, sizeof(*fl.items), cmp_path);
	for (i = 0; i < fl.count; ++i)
	{
		char full[FCC_PATH], buf[65536];
		const char *p;
		FILE *f;
		size_t n;
		for (p = fl.items[i]; *p; ++p)
		{
			char c = (*p >= 'A' && *p <= 'Z') ? (char)(*p - 'A' + 'a') : *p;
			h = (h ^ (unsigned char)c) * prime;
		}
		h = (h ^ 0) * prime;
		path_join(full, sizeof(full), dir, fl.items[i]);
		f = fopen(full, "rb");
		if (!f)
			continue;
		while ((n = fread(buf, 1, sizeof(buf), f)) > 0)
		{
			size_t k;
			for (k = 0; k < n; ++k)
				h = (h ^ (unsigned char)buf[k]) * prime;
		}
		fclose(f);
	}
	free(fl.items);
	sprintf(hex, "%08lx%08lx", (unsigned long)(h >> 32), (unsigned long)(h & 0xffffffffUL));
	return 1;
}

/* ---- install ------------------------------------------------------------------------------------------- */

static int copy_tree(const char *src, const char *dst, char *why, size_t whySize)
{
	char pattern[FCC_PATH];
	WIN32_FIND_DATAA fd;
	HANDLE h;

	if (!CreateDirectoryA(dst, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
	{
		_snprintf(why, whySize, "cannot create %s (error %lu)", dst, GetLastError());
		return 0;
	}
	path_join(pattern, sizeof(pattern), src, "*");
	h = FindFirstFileA(pattern, &fd);
	if (h == INVALID_HANDLE_VALUE)
		return 1;
	do
	{
		char s[FCC_PATH], d[FCC_PATH];
		if (!strcmp(fd.cFileName, ".") || !strcmp(fd.cFileName, ".."))
			continue;
		path_join(s, sizeof(s), src, fd.cFileName);
		path_join(d, sizeof(d), dst, fd.cFileName);
		if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
		{
			if (!copy_tree(s, d, why, whySize))
			{
				FindClose(h);
				return 0;
			}
		}
		else if (!CopyFileA(s, d, FALSE))
		{
			_snprintf(why, whySize, "cannot copy %s to %s (error %lu)", s, d, GetLastError());
			FindClose(h);
			return 0;
		}
	} while (FindNextFileA(h, &fd));
	FindClose(h);
	return 1;
}

int mod_install(GameInfo *g, char *why, size_t whySize)
{
	char src[FCC_PATH], mods[FCC_PATH], srcBuild[17];
	size_t rootLen = strlen(g->root);

	why[0] = 0;
	if (!mod_source_dir(src, sizeof(src)))
	{
		_snprintf(why, whySize, "mod files not found next to the launcher (%s); extract the whole package", src);
		return 0;
	}
	if (!mod_build_id(src, srcBuild))
	{
		_snprintf(why, whySize, "mod folder %s is empty", src);
		return 0;
	}
	/* Never write anywhere but <root>\Mods\FarCryCoop. */
	if (_strnicmp(g->modDir, g->root, rootLen) != 0 || _stricmp(g->modDir + rootLen, "\\Mods\\" MOD_NAME) != 0)
	{
		_snprintf(why, whySize, "refusing to install outside %s\\Mods\\%s", g->root, MOD_NAME);
		return 0;
	}
	if (dir_exists(g->modDir))
	{
		char current[17] = "";
		mod_build_id(g->modDir, current);
		if (!strcmp(current, srcBuild))
		{
			log_msg("mod already installed and up to date (build %s)", srcBuild);
			strcpy(g->modBuild, srcBuild);
			g->modInstalled = 1;
			return 1;
		}
		{
			char backup[FCC_PATH], stamp[32];
			time_t t = time(NULL);
			strftime(stamp, sizeof(stamp), "%Y%m%d-%H%M%S", localtime(&t));
			_snprintf(backup, sizeof(backup), "%s.backup-%s", g->modDir, stamp);
			backup[sizeof(backup) - 1] = 0;
			if (!MoveFileA(g->modDir, backup))
			{
				_snprintf(why, whySize, "cannot back up the existing %s (error %lu); is Far Cry running?", g->modDir, GetLastError());
				return 0;
			}
			log_msg("backed up the previous mod folder (build %s) to %s", current[0] ? current : "unknown", backup);
		}
	}
	path_join(mods, sizeof(mods), g->root, "Mods");
	if (!CreateDirectoryA(mods, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
	{
		_snprintf(why, whySize, "cannot create %s (error %lu)", mods, GetLastError());
		return 0;
	}
	if (!copy_tree(src, g->modDir, why, whySize))
		return 0;
	if (!mod_build_id(g->modDir, g->modBuild) || strcmp(g->modBuild, srcBuild) != 0)
	{
		_snprintf(why, whySize, "installed files do not match the package (build %s vs %s)", g->modBuild, srcBuild);
		return 0;
	}
	g->modInstalled = 1;
	log_msg("installed %s build %s into %s", MOD_NAME, g->modBuild, g->modDir);
	return 1;
}

int game_launch(const GameInfo *g, const char *extraArgs, int dryRun, HANDLE *process, DWORD *pid)
{
	char cmd[2048], cwd[FCC_PATH];
	STARTUPINFOA si;
	PROCESS_INFORMATION pi;

	_snprintf(cmd, sizeof(cmd), "\"%s\" -MOD:%s %s", g->exe, MOD_NAME, extraArgs ? extraArgs : "");
	cmd[sizeof(cmd) - 1] = 0;
	path_join(cwd, sizeof(cwd), g->root, "Bin32");
	log_msg("%s: %s (in %s)", dryRun ? "would run" : "starting", cmd, cwd);
	if (dryRun)
		return 1;
	memset(&si, 0, sizeof(si));
	si.cb = sizeof(si);
	if (!CreateProcessA(g->exe, cmd, NULL, NULL, FALSE, 0, NULL, cwd, &si, &pi))
	{
		log_msg("ERROR: could not start %s (error %lu)", g->exe, GetLastError());
		return 0;
	}
	CloseHandle(pi.hThread);
	if (process)
		*process = pi.hProcess;
	else
		CloseHandle(pi.hProcess);
	if (pid)
		*pid = pi.dwProcessId;
	log_msg("Far Cry started (pid %lu)", pi.dwProcessId);
	return 1;
}
