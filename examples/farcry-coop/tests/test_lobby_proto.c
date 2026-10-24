/* Unit + fuzz tests for the LAN lobby protocol (launcher/lobby_proto.c).
 * Native:  gcc -fsanitize=address,undefined -I../launcher test_lobby_proto.c ../launcher/lobby_proto.c
 * Windows: i686-w64-mingw32-gcc -I../launcher test_lobby_proto.c ../launcher/lobby_proto.c (runs under wine) */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "lobby_proto.h"

static int fails, checks;
#define CHECK(c) do { ++checks; if (!(c)) { ++fails; printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); } } while (0)

static LobbySession sample(void)
{
	LobbySession s;
	memset(&s, 0, sizeof(s));
	strcpy(s.name, "Jack and Val");
	strcpy(s.map, "pier");
	strcpy(s.build, "1a2b3c4d");
	s.port = 49001;
	s.maxPlayers = 4;
	return s;
}

int main(void)
{
	char buf[LOBBY_MAX_DATAGRAM + 64];
	unsigned nonce = 0;
	LobbySession s = sample(), got;
	int n, i;

	n = lobby_make_probe(123456789u, buf, sizeof(buf));
	CHECK(n > 0 && lobby_parse_probe(buf, n, &nonce) == 1 && nonce == 123456789u);
	CHECK(lobby_parse_probe("FCCOOP/1 PROBE 12x", 18, &nonce) == 0);
	CHECK(lobby_parse_probe("FCCOOP/2 PROBE 12", 17, &nonce) == 0);
	CHECK(lobby_parse_probe("FCCOOP/1 PROBE 99999999999", 26, &nonce) == 0);
	CHECK(lobby_parse_probe("", 0, &nonce) == 0);

	n = lobby_make_session(77u, &s, buf, sizeof(buf));
	CHECK(n > 0);
	CHECK(lobby_parse_session(buf, n, 77u, &got) == 1);
	CHECK(!strcmp(got.name, s.name) && !strcmp(got.map, "pier") && got.port == 49001 && got.maxPlayers == 4);
	CHECK(!strcmp(got.build, "1a2b3c4d") && got.hasPassword == 0 && got.mission[0] == 0);
	CHECK(lobby_parse_session(buf, n, 78u, &got) == 0);              /* not our probe */
	CHECK(lobby_parse_session(buf, n - 5, 77u, &got) == 1);           /* pw is optional */
	CHECK(lobby_parse_session(buf, (int)(strstr(buf, ";build") - buf), 77u, &got) == 0); /* build is required */

	strcpy(s.mission, "Mission2"); s.hasPassword = 1;
	n = lobby_make_session(5u, &s, buf, sizeof(buf));
	CHECK(lobby_parse_session(buf, n, 5u, &got) == 1 && !strcmp(got.mission, "Mission2") && got.hasPassword == 1);

	/* the host refuses to advertise unsafe values */
	s = sample(); strcpy(s.name, "a;map=evil"); CHECK(lobby_make_session(1, &s, buf, sizeof(buf)) == -1);
	s = sample(); strcpy(s.map, "../x"); CHECK(lobby_make_session(1, &s, buf, sizeof(buf)) == -1);
	s = sample(); s.port = 0; CHECK(lobby_make_session(1, &s, buf, sizeof(buf)) == -1);
	s = sample(); s.maxPlayers = 17; CHECK(lobby_make_session(1, &s, buf, sizeof(buf)) == -1);

	/* the client refuses malformed answers */
#define BAD(str) CHECK(lobby_parse_session(str, (int)strlen(str), 9u, &got) == 0)
	BAD("FCCOOP/1 SESSION 9 name=a;map=b;port=1;max=2");                         /* no build */
	BAD("FCCOOP/1 SESSION 9 name=a;map=b\";port=1;max=2;build=x");               /* bad map */
	BAD("FCCOOP/1 SESSION 9 name=a;map=b;port=70000;max=2;build=x");            /* port */
	BAD("FCCOOP/1 SESSION 9 name=a;map=b;port=1;max=99;build=x");               /* max */
	BAD("FCCOOP/1 SESSION 9 name=a;map=b;port=1;max=2;build=x;pw=yes");         /* pw */
	BAD("FCCOOP/1 SESSION 9 name=a;map=b;port=1;max=2;build=x;garbage");        /* no '=' */
	BAD("FCCOOP/1 SESSION 9name=a;map=b;port=1;max=2;build=x");                 /* no space */
	CHECK(lobby_parse_session("FCCOOP/1 SESSION 9 name=a;map=b;port=1;max=2;build=x;future=1", 61, 9u, &got) == 1);
	{
		char nonascii[] = "FCCOOP/1 SESSION 9 name=\xe9;map=b;port=1;max=2;build=x";
		CHECK(lobby_parse_session(nonascii, (int)strlen(nonascii), 9u, &got) == 0);
	}
	memset(buf, 'A', sizeof(buf));
	CHECK(lobby_parse_session(buf, LOBBY_MAX_DATAGRAM + 1, 9u, &got) == 0);   /* oversize */
	CHECK(lobby_parse_probe(buf, LOBBY_MAX_DATAGRAM + 1, &nonce) == 0);

	/* fuzz: random and mutated datagrams must never crash or be accepted for the wrong nonce */
	srand(1234);
	for (i = 0; i < 200000; ++i)
	{
		char d[LOBBY_MAX_DATAGRAM + 8];
		int len = rand() % (LOBBY_MAX_DATAGRAM + 4), k;
		if (i & 1)
		{
			LobbySession base = sample();
			len = lobby_make_session(42u, &base, d, sizeof(d));
			for (k = 0; k < 3; ++k) d[rand() % len] = (char)(rand() & 0xff);
		}
		else
			for (k = 0; k < len; ++k) d[k] = (char)(rand() & 0xff);
		lobby_parse_probe(d, len, &nonce);
		if (lobby_parse_session(d, len, 41u, &got))
		{
			++fails; printf("FAIL: fuzz accepted a session for the wrong nonce\n"); break;
		}
		lobby_parse_session(d, len, 42u, &got);
	}
	++checks;

	printf("%d checks, %d failed\n", checks, fails);
	return fails ? 1 : 0;
}
