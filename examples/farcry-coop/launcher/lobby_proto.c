/* FarCryCoop LAN lobby protocol. See lobby_proto.h. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "lobby_proto.h"

static const char MAGIC[] = "FCCOOP/1 ";

static int is_alnum(char c)
{
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}

int lobby_valid_text(const char *v)
{
	if (!v || !*v || strlen(v) >= LOBBY_FIELD_MAX)
		return 0;
	for (; *v; ++v)
		if (!is_alnum(*v) && !strchr(" _-.!#", *v))
			return 0;
	return 1;
}

int lobby_valid_ident(const char *v)
{
	if (!v || !*v || strlen(v) >= LOBBY_FIELD_MAX)
		return 0;
	for (; *v; ++v)
		if (!is_alnum(*v) && *v != '_' && *v != '-')
			return 0;
	return 1;
}

/* Copies a datagram into a NUL-terminated buffer after checking it is printable ASCII. */
static int take(const char *data, int len, char *out, int size)
{
	int i;
	if (!data || len <= 0 || len > LOBBY_MAX_DATAGRAM || len >= size)
		return 0;
	for (i = 0; i < len; ++i)
		if ((unsigned char)data[i] < 0x20 || (unsigned char)data[i] > 0x7e)
			return 0;
	memcpy(out, data, (size_t)len);
	out[len] = 0;
	return 1;
}

static int parse_nonce(const char *s, unsigned *nonce, const char **rest)
{
	char *end;
	unsigned long v;
	if (*s < '0' || *s > '9')
		return 0;
	v = strtoul(s, &end, 10);
	if (end == s || end - s > 10 || v > 0xffffffffUL)
		return 0;
	*nonce = (unsigned)v;
	*rest = end;
	return 1;
}

int lobby_make_probe(unsigned nonce, char *buf, int size)
{
	int n = snprintf(buf, (size_t)size, "%sPROBE %u", MAGIC, nonce);
	return (n < 0 || n >= size) ? -1 : n;
}

int lobby_parse_probe(const char *data, int len, unsigned *nonce)
{
	char tmp[LOBBY_MAX_DATAGRAM + 1];
	const char *rest;
	if (!take(data, len, tmp, sizeof(tmp)))
		return 0;
	if (strncmp(tmp, MAGIC, sizeof(MAGIC) - 1) != 0 || strncmp(tmp + sizeof(MAGIC) - 1, "PROBE ", 6) != 0)
		return 0;
	if (!parse_nonce(tmp + sizeof(MAGIC) - 1 + 6, nonce, &rest))
		return 0;
	return *rest == 0;
}

int lobby_make_session(unsigned nonce, const LobbySession *s, char *buf, int size)
{
	int n;
	if (!lobby_valid_text(s->name) || !lobby_valid_ident(s->map) || !lobby_valid_ident(s->build))
		return -1;
	if (s->mission[0] && !lobby_valid_ident(s->mission))
		return -1;
	if (s->port < 1 || s->port > 65535 || s->maxPlayers < 2 || s->maxPlayers > 16)
		return -1;
	n = snprintf(buf, (size_t)size, "%sSESSION %u name=%s;map=%s;mission=%s;port=%d;max=%d;build=%s;pw=%d",
		MAGIC, nonce, s->name, s->map, s->mission, s->port, s->maxPlayers, s->build, s->hasPassword ? 1 : 0);
	return (n < 0 || n >= size || n > LOBBY_MAX_DATAGRAM) ? -1 : n;
}

static int parse_int(const char *v, int lo, int hi, int *out)
{
	char *end;
	long x;
	if (!*v || strlen(v) > 5)
		return 0;
	x = strtol(v, &end, 10);
	if (*end || x < lo || x > hi)
		return 0;
	*out = (int)x;
	return 1;
}

int lobby_parse_session(const char *data, int len, unsigned nonce, LobbySession *out)
{
	char tmp[LOBBY_MAX_DATAGRAM + 1];
	const char *rest;
	char *field, *next;
	unsigned got;
	int seen = 0;

	memset(out, 0, sizeof(*out));
	if (!take(data, len, tmp, sizeof(tmp)))
		return 0;
	if (strncmp(tmp, MAGIC, sizeof(MAGIC) - 1) != 0 || strncmp(tmp + sizeof(MAGIC) - 1, "SESSION ", 8) != 0)
		return 0;
	if (!parse_nonce(tmp + sizeof(MAGIC) - 1 + 8, &got, &rest) || got != nonce || *rest != ' ')
		return 0;

	for (field = (char *)rest + 1; field && *field; field = next)
	{
		char *eq;
		next = strchr(field, ';');
		if (next)
			*next++ = 0;
		eq = strchr(field, '=');
		if (!eq)
			return 0;
		*eq = 0;
		{
			const char *k = field, *v = eq + 1;
			if (!strcmp(k, "name")) { if (!lobby_valid_text(v)) return 0; strcpy(out->name, v); seen |= 1; }
			else if (!strcmp(k, "map")) { if (!lobby_valid_ident(v)) return 0; strcpy(out->map, v); seen |= 2; }
			else if (!strcmp(k, "mission")) { if (*v && !lobby_valid_ident(v)) return 0; strcpy(out->mission, v); }
			else if (!strcmp(k, "port")) { if (!parse_int(v, 1, 65535, &out->port)) return 0; seen |= 4; }
			else if (!strcmp(k, "max")) { if (!parse_int(v, 2, 16, &out->maxPlayers)) return 0; seen |= 8; }
			else if (!strcmp(k, "build")) { if (!lobby_valid_ident(v)) return 0; strcpy(out->build, v); seen |= 16; }
			else if (!strcmp(k, "pw")) { if (strcmp(v, "0") && strcmp(v, "1")) return 0; out->hasPassword = v[0] == '1'; }
			/* unknown keys are ignored so later versions can add fields */
		}
	}
	return seen == 31;
}
