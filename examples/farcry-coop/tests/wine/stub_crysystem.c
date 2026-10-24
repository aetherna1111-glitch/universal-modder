/* Test stub for CrySystem.dll: only the allocator exports that CryGame.dll's CryMemoryManager.h binds to at
 * load time (CryMalloc/CryRealloc/CryReallocSize/CryFree/CryFreeSize). Used by the wine load test. */
#include <windows.h>

static LONG g_allocs;

__declspec(dllexport) void *CryMalloc(size_t size) { InterlockedIncrement(&g_allocs); return HeapAlloc(GetProcessHeap(), 0, size ? size : 1); }
__declspec(dllexport) void *CryRealloc(void *p, size_t size)
{
	if (!p) return CryMalloc(size);
	return HeapReAlloc(GetProcessHeap(), 0, p, size ? size : 1);
}
__declspec(dllexport) void *CryReallocSize(void *p, size_t oldsize, size_t size) { (void)oldsize; return CryRealloc(p, size); }
__declspec(dllexport) void CryFree(void *p) { if (p) HeapFree(GetProcessHeap(), 0, p); }
__declspec(dllexport) void CryFreeSize(void *p, size_t size) { (void)size; CryFree(p); }
__declspec(dllexport) LONG StubAllocCount(void) { return g_allocs; }
