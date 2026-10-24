/* FarCryCoop launcher: logging and small path helpers. */
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>
#include "util.h"

static char g_exeDir[FCC_PATH];
static char g_logPath[FCC_PATH];
static void (*g_sink)(const char *line);
static CRITICAL_SECTION g_logLock;

const char *exe_dir(void)
{
	if (!g_exeDir[0])
	{
		char *slash;
		GetModuleFileNameA(NULL, g_exeDir, sizeof(g_exeDir));
		slash = strrchr(g_exeDir, '\\');
		if (slash)
			*slash = 0;
	}
	return g_exeDir;
}

void log_init(void)
{
	InitializeCriticalSection(&g_logLock);
	path_join(g_logPath, sizeof(g_logPath), exe_dir(), "FarCryCoop.log");
}

const char *log_path(void)
{
	return g_logPath;
}

void log_set_sink(void (*sink)(const char *line))
{
	g_sink = sink;
}

void log_msg(const char *fmt, ...)
{
	char line[1200], stamp[32];
	va_list ap;
	time_t t = time(NULL);
	FILE *f;

	va_start(ap, fmt);
	_vsnprintf(line, sizeof(line) - 1, fmt, ap);
	va_end(ap);
	line[sizeof(line) - 1] = 0;
	strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", localtime(&t));

	EnterCriticalSection(&g_logLock);
	f = fopen(g_logPath, "a");
	if (f)
	{
		fprintf(f, "%s %s\n", stamp, line);
		fclose(f);
	}
	printf("%s\n", line);
	fflush(stdout);
	if (g_sink)
		g_sink(line);
	LeaveCriticalSection(&g_logLock);
}

int path_join(char *out, size_t size, const char *a, const char *b)
{
	size_t la = strlen(a);
	int n = _snprintf(out, size, (la && (a[la - 1] == '\\' || a[la - 1] == '/')) ? "%s%s" : "%s\\%s", a, b);
	if (n < 0 || (size_t)n >= size)
	{
		out[size - 1] = 0;
		return 0;
	}
	return 1;
}

int file_exists(const char *path)
{
	DWORD a = GetFileAttributesA(path);
	return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

int dir_exists(const char *path)
{
	DWORD a = GetFileAttributesA(path);
	return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}
