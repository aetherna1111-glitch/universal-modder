/*
 * FarCryCoop LAN lobby protocol (launcher <-> launcher, UDP).
 *
 * The game's own netcode carries the actual session (CryNetwork, UDP 49001 by default). This small protocol
 * only lets a joining launcher find hosts on the LAN and check that both sides run the same FarCryCoop
 * build before Far Cry is started, because the engine only checks the mod *name*.
 *
 *   probe  (client -> broadcast / host, UDP LOBBY_PORT):  "FCCOOP/1 PROBE <nonce>"
 *   answer (host -> client, unicast):                     "FCCOOP/1 SESSION <nonce> <key>=<value>;..."
 *
 * Keys: name, map, mission, port, max, build, pw. Datagrams are plain ASCII, at most LOBBY_MAX_DATAGRAM
 * bytes; anything else is dropped. The nonce ties an answer to our own probe. Portable C (Winsock or
 * BSD sockets are not used here), so the unit tests build natively and under mingw.
 */
#ifndef FARCRY_COOP_LOBBY_PROTO_H
#define FARCRY_COOP_LOBBY_PROTO_H

#ifdef __cplusplus
extern "C" {
#endif

#define LOBBY_PORT 49099
#define LOBBY_MAX_DATAGRAM 512
#define LOBBY_FIELD_MAX 64

typedef struct LobbySession
{
	char name[LOBBY_FIELD_MAX];
	char map[LOBBY_FIELD_MAX];
	char mission[LOBBY_FIELD_MAX];
	char build[LOBBY_FIELD_MAX];     /* hex digest of the host's CryGame.dll + rules */
	int port;                        /* Far Cry server port (sv_port) */
	int maxPlayers;
	int hasPassword;
} LobbySession;

/* Builds a probe into buf. Returns its length, or -1 if it does not fit. */
int lobby_make_probe(unsigned nonce, char *buf, int size);

/* Parses a probe. Returns 1 and sets *nonce on success, 0 otherwise. */
int lobby_parse_probe(const char *data, int len, unsigned *nonce);

/* Builds an answer. Returns its length, or -1 when a field is invalid or it does not fit. */
int lobby_make_session(unsigned nonce, const LobbySession *s, char *buf, int size);

/* Parses an answer to the probe with this nonce. Returns 1 on success, 0 otherwise. */
int lobby_parse_session(const char *data, int len, unsigned nonce, LobbySession *out);

/* Field validation shared with the launcher UI: 1 if value only uses the allowed characters. */
int lobby_valid_text(const char *value);   /* names: letters, digits, space _ - . ! # */
int lobby_valid_ident(const char *value);  /* map / mission / build: letters, digits, _ - */

#ifdef __cplusplus
}
#endif

#endif
