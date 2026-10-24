/* Wine/Windows smoke test: load CryGame.dll like the Far Cry engine does and construct the game object.
 * Checks: the PE loads (imports resolve), static initialisers run, the CryMemoryManager binds to
 * CrySystem.dll's allocator, CreateGameInstance and CryModuleGetMemoryInfo are exported, and
 * CreateGameInstance() returns an object. It does not call IGame::Init (that needs the real engine). */
#include <windows.h>
#include <stdio.h>

typedef void *(*FN_Create)(void);
typedef LONG (*FN_Count)(void);

int main(int argc, char **argv)
{
	const char *dll = argc > 1 ? argv[1] : "CryGame.dll";
	HMODULE sys = LoadLibraryA("CrySystem.dll");
	if (!sys) { printf("FAIL: stub CrySystem.dll not loadable (%lu)\n", GetLastError()); return 1; }
	FN_Count count = (FN_Count)GetProcAddress(sys, "StubAllocCount");

	HMODULE game = LoadLibraryA(dll);
	if (!game) { printf("FAIL: LoadLibrary(%s) error %lu\n", dll, GetLastError()); return 1; }
	printf("loaded %s, allocations through CrySystem so far: %ld\n", dll, count());

	FN_Create create = (FN_Create)GetProcAddress(game, "CreateGameInstance");
	FARPROC meminfo = GetProcAddress(game, "CryModuleGetMemoryInfo");
	if (!create || !meminfo) { printf("FAIL: missing export (CreateGameInstance=%p CryModuleGetMemoryInfo=%p)\n", (void *)create, (void *)meminfo); return 1; }

	long before = count();
	void *pGame = create();
	if (!pGame) { printf("FAIL: CreateGameInstance returned NULL\n"); return 1; }
	printf("CreateGameInstance() = %p, allocations by the constructor: %ld\n", pGame, count() - before);
	if (count() - before <= 0) { printf("FAIL: game object was not allocated through CrySystem's CryMalloc\n"); return 1; }
	printf("OK\n");
	return 0;
}
