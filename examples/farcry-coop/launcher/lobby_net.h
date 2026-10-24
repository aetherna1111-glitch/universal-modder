/* FarCryCoop launcher: LAN discovery over UDP (Winsock). Protocol in lobby_proto.h. */
#ifndef FARCRY_COOP_LOBBY_NET_H
#define FARCRY_COOP_LOBBY_NET_H

#include "lobby_proto.h"

typedef struct LobbyHost LobbyHost;

typedef struct LobbyFound
{
	LobbySession session;
	char address[64];   /* IPv4 address the answer came from */
	int pingMs;         /* probe -> answer round trip */
} LobbyFound;

int lobby_net_init(void);

/* Answers probes on UDP `port` in a background thread until stopped. NULL on failure (why filled). */
LobbyHost *lobby_host_start(const LobbySession *s, int port, char *why, size_t whySize);
void lobby_host_stop(LobbyHost *h);
int lobby_host_answers(LobbyHost *h);

/* Sends a probe to the LAN broadcast address, to 127.0.0.1 and to `target` (if not NULL), then collects
 * answers for timeoutMs. Returns the number of distinct sessions found (<= max), or -1 on socket errors. */
int lobby_discover(int timeoutMs, const char *target, int port, LobbyFound *out, int max);

#endif
