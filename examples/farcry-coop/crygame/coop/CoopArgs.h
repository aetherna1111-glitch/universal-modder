/*
 * FarCryCoop: launch request passed on the Far Cry command line by FarCryCoop.exe.
 *
 *   -coophost:<map>[:<mission>]   host a co-op listen server on Levels/<map>
 *   -coopjoin:<address>           join a co-op server (IPv4 address or host name)
 *   -coopname:<server name>       sv_name for a hosted session
 *   -coopmax:<2..16>              sv_maxplayers for a hosted session
 *   -coopport:<1024..65535>       sv_port for a hosted session
 *   -cooppass:<password>          sv_password for a hosted session
 *
 * Every value ends up inside a Lua string or a console variable, so the parser only accepts a strict
 * character set and rejects the whole request on anything else. Plain C with no engine headers, so the
 * unit tests build it natively.
 */
#ifndef FARCRY_COOP_ARGS_H
#define FARCRY_COOP_ARGS_H

#ifdef __cplusplus
extern "C" {
#endif

enum { COOP_REQ_NONE = 0, COOP_REQ_HOST = 1, COOP_REQ_JOIN = 2 };

#define COOP_ARG_MAX 64

typedef struct SCoopLaunchRequest
{
	int type;                       /* COOP_REQ_* */
	char map[COOP_ARG_MAX];
	char mission[COOP_ARG_MAX];
	char address[COOP_ARG_MAX];
	char name[COOP_ARG_MAX];
	char password[COOP_ARG_MAX];
	int maxPlayers;                 /* 0 = leave sv_maxplayers alone */
	int port;                       /* 0 = leave sv_port alone */
	char error[128];                /* set when the parse fails */
} SCoopLaunchRequest;

/* Returns 1 and fills req for a valid request, 0 when there is none, -1 on an invalid one (req->error). */
int Coop_ParseLaunchArgs(const char *cmdline, SCoopLaunchRequest *req);

#ifdef __cplusplus
}
#endif

#endif /* FARCRY_COOP_ARGS_H */
