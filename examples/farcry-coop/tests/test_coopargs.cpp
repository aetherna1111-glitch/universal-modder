// Unit tests for the FarCryCoop launch-argument parser (crygame/coop/CoopArgs.cpp). Native build:
//   g++ -I../crygame/coop test_coopargs.cpp ../crygame/coop/CoopArgs.cpp -o test_coopargs && ./test_coopargs
#include <stdio.h>
#include <string.h>
#include "CoopArgs.h"

static int g_fail = 0, g_count = 0;
#define CHECK(cond) do { ++g_count; if (!(cond)) { ++g_fail; printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)

int main()
{
	SCoopLaunchRequest r;

	CHECK(Coop_ParseLaunchArgs(NULL, &r) == 0);
	CHECK(Coop_ParseLaunchArgs("\"C:\\Games\\Far Cry\\Bin32\\FarCry.exe\" -MOD:FarCryCoop", &r) == 0);

	CHECK(Coop_ParseLaunchArgs("FarCry.exe -MOD:FarCryCoop -coophost:training", &r) == 1);
	CHECK(r.type == COOP_REQ_HOST && !strcmp(r.map, "training") && r.mission[0] == 0);

	CHECK(Coop_ParseLaunchArgs("x -coophost:pier:Mission1 -coopmax:4 -coopport:49010 \"-coopname:Jack and Val\" -cooppass:s3cret", &r) == 1);
	CHECK(!strcmp(r.map, "pier") && !strcmp(r.mission, "Mission1"));
	CHECK(r.maxPlayers == 4 && r.port == 49010);
	CHECK(!strcmp(r.name, "Jack and Val") && !strcmp(r.password, "s3cret"));

	CHECK(Coop_ParseLaunchArgs("x -coopjoin:192.168.1.20", &r) == 1);
	CHECK(r.type == COOP_REQ_JOIN && !strcmp(r.address, "192.168.1.20"));
	CHECK(Coop_ParseLaunchArgs("x -COOPJOIN:host-name.lan:49001", &r) == 1);
	CHECK(!strcmp(r.address, "host-name.lan:49001"));

	// Anything that could escape the Lua string or the console is refused outright.
	CHECK(Coop_ParseLaunchArgs("x -coophost:training\")System:Quit()--", &r) == -1 && r.type == COOP_REQ_NONE);
	CHECK(Coop_ParseLaunchArgs("x -coopjoin:1.2.3.4\");os.exit()", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:../../etc", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:a\\b", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:training -coopname:a;b", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:training -coopmax:99", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:training -coopmax:1", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:training -coopport:80", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:training -coopport:123456", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coophost:training -coopjoin:1.2.3.4", &r) == -1);
	CHECK(Coop_ParseLaunchArgs("x -coopfoo:1", &r) == -1);

	char longArg[300] = "x -coophost:";
	for (int i = 0; i < 200; ++i) strcat(longArg, "a");
	CHECK(Coop_ParseLaunchArgs(longArg, &r) == -1);
	CHECK(strstr(r.error, "length") != NULL);

	printf("%d checks, %d failed\n", g_count, g_fail);
	return g_fail ? 1 : 0;
}
