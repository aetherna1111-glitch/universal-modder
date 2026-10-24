/* Test fixture standing in for Bin32\FarCry.exe: records how the launcher started it. Not game code. */
#include <windows.h>
#include <stdio.h>
int WINAPI WinMain(HINSTANCE a, HINSTANCE b, LPSTR c, int d)
{
	char cwd[MAX_PATH], path[MAX_PATH + 32];
	FILE *f;
	(void)a; (void)b; (void)c; (void)d;
	GetCurrentDirectoryA(sizeof(cwd), cwd);
	GetModuleFileNameA(NULL, path, MAX_PATH);
	strcpy(strrchr(path, '\\') + 1, "launched.txt");
	f = fopen(path, "w");
	if (f)
	{
		fprintf(f, "cmdline=%s\ncwd=%s\n", GetCommandLineA(), cwd);
		fclose(f);
	}
	return 7;
}
