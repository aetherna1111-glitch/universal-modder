/* FarCryCoop launcher: LAN discovery over UDP (Winsock). */
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "lobby_net.h"
#include "util.h"

struct LobbyHost
{
	SOCKET sock;
	HANDLE thread;
	volatile LONG stop;
	volatile LONG answers;
	LobbySession session;
};

int lobby_net_init(void)
{
	WSADATA wsa;
	int r = WSAStartup(MAKEWORD(2, 2), &wsa);
	if (r != 0)
		log_msg("ERROR: WSAStartup failed (%d)", r);
	return r == 0;
}

static DWORD WINAPI host_thread(LPVOID arg)
{
	LobbyHost *h = (LobbyHost *)arg;
	while (!h->stop)
	{
		fd_set rd;
		struct timeval tv;
		char buf[LOBBY_MAX_DATAGRAM + 1], reply[LOBBY_MAX_DATAGRAM + 1];
		struct sockaddr_in from;
		int fromLen = sizeof(from), n;
		unsigned nonce;

		FD_ZERO(&rd);
		FD_SET(h->sock, &rd);
		tv.tv_sec = 0;
		tv.tv_usec = 200000;
		if (select(0, &rd, NULL, NULL, &tv) <= 0)
			continue;
		n = recvfrom(h->sock, buf, sizeof(buf), 0, (struct sockaddr *)&from, &fromLen);
		if (n <= 0 || n > LOBBY_MAX_DATAGRAM)
			continue;                       /* errors, empty and oversize datagrams are dropped */
		if (!lobby_parse_probe(buf, n, &nonce))
			continue;
		n = lobby_make_session(nonce, &h->session, reply, sizeof(reply));
		if (n <= 0)
			continue;
		if (sendto(h->sock, reply, n, 0, (struct sockaddr *)&from, fromLen) == n)
		{
			InterlockedIncrement(&h->answers);
			log_msg("lobby: answered probe from %s:%d", inet_ntoa(from.sin_addr), ntohs(from.sin_port));
		}
	}
	return 0;
}

LobbyHost *lobby_host_start(const LobbySession *s, int port, char *why, size_t whySize)
{
	LobbyHost *h;
	struct sockaddr_in addr;
	BOOL yes = TRUE;
	char test[LOBBY_MAX_DATAGRAM + 1];

	if (lobby_make_session(0, s, test, sizeof(test)) < 0)
	{
		_snprintf(why, whySize, "session details contain characters that cannot be advertised");
		return NULL;
	}
	h = (LobbyHost *)calloc(1, sizeof(*h));
	h->session = *s;
	h->sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
	if (h->sock == INVALID_SOCKET)
	{
		_snprintf(why, whySize, "socket() failed (%d)", WSAGetLastError());
		free(h);
		return NULL;
	}
	setsockopt(h->sock, SOL_SOCKET, SO_REUSEADDR, (const char *)&yes, sizeof(yes));
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_port = htons((unsigned short)port);
	addr.sin_addr.s_addr = htonl(INADDR_ANY);
	if (bind(h->sock, (struct sockaddr *)&addr, sizeof(addr)) != 0)
	{
		_snprintf(why, whySize, "cannot listen on UDP %d (%d); another launcher may be hosting on this PC", port, WSAGetLastError());
		closesocket(h->sock);
		free(h);
		return NULL;
	}
	h->thread = CreateThread(NULL, 0, host_thread, h, 0, NULL);
	log_msg("lobby: answering LAN probes on UDP %d for '%s' (%s)", port, s->name, s->map);
	return h;
}

void lobby_host_stop(LobbyHost *h)
{
	if (!h)
		return;
	InterlockedExchange(&h->stop, 1);
	WaitForSingleObject(h->thread, 2000);
	CloseHandle(h->thread);
	closesocket(h->sock);
	log_msg("lobby: stopped answering probes (%ld answers sent)", h->answers);
	free(h);
}

int lobby_host_answers(LobbyHost *h)
{
	return h ? (int)h->answers : 0;
}

static void send_probe(SOCKET s, const char *ip, int port, const char *probe, int len)
{
	struct sockaddr_in to;
	memset(&to, 0, sizeof(to));
	to.sin_family = AF_INET;
	to.sin_port = htons((unsigned short)port);
	to.sin_addr.s_addr = inet_addr(ip);
	if (to.sin_addr.s_addr == INADDR_NONE && strcmp(ip, "255.255.255.255") != 0)
	{
		struct hostent *he = gethostbyname(ip);
		if (!he)
		{
			log_msg("lobby: cannot resolve %s", ip);
			return;
		}
		memcpy(&to.sin_addr, he->h_addr_list[0], sizeof(to.sin_addr));
	}
	if (sendto(s, probe, len, 0, (struct sockaddr *)&to, sizeof(to)) != len)
		log_msg("lobby: probe to %s:%d failed (%d)", ip, port, WSAGetLastError());
}

int lobby_discover(int timeoutMs, const char *target, int port, LobbyFound *out, int max)
{
	SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
	BOOL yes = TRUE;
	char probe[64];
	unsigned nonce;
	int len, found = 0;
	DWORD start;

	if (s == INVALID_SOCKET)
	{
		log_msg("ERROR: socket() failed (%d)", WSAGetLastError());
		return -1;
	}
	setsockopt(s, SOL_SOCKET, SO_BROADCAST, (const char *)&yes, sizeof(yes));
	nonce = (unsigned)GetTickCount() ^ ((unsigned)GetCurrentProcessId() << 16) ^ (unsigned)rand();
	len = lobby_make_probe(nonce, probe, sizeof(probe));
	start = GetTickCount();
	send_probe(s, "255.255.255.255", port, probe, len);
	send_probe(s, "127.0.0.1", port, probe, len);
	if (target && *target)
		send_probe(s, target, port, probe, len);

	while ((int)(GetTickCount() - start) < timeoutMs)
	{
		fd_set rd;
		struct timeval tv;
		char buf[LOBBY_MAX_DATAGRAM + 1];
		struct sockaddr_in from;
		int fromLen = sizeof(from), n, i, dup = 0;
		LobbyFound f;
		int left = timeoutMs - (int)(GetTickCount() - start);

		FD_ZERO(&rd);
		FD_SET(s, &rd);
		tv.tv_sec = left / 1000;
		tv.tv_usec = (left % 1000) * 1000;
		if (select(0, &rd, NULL, NULL, &tv) <= 0)
			break;
		n = recvfrom(s, buf, sizeof(buf), 0, (struct sockaddr *)&from, &fromLen);
		if (n <= 0 || n > LOBBY_MAX_DATAGRAM)
			continue;
		memset(&f, 0, sizeof(f));
		if (!lobby_parse_session(buf, n, nonce, &f.session))
		{
			log_msg("lobby: ignored an invalid answer from %s", inet_ntoa(from.sin_addr));
			continue;
		}
		strncpy(f.address, inet_ntoa(from.sin_addr), sizeof(f.address) - 1);
		f.pingMs = (int)(GetTickCount() - start);
		for (i = 0; i < found; ++i)
			if (out[i].session.port == f.session.port && !strcmp(out[i].session.name, f.session.name)
				&& !strcmp(out[i].session.build, f.session.build))
				dup = 1;           /* the same host answered via broadcast and loopback */
		if (dup || found >= max)
			continue;
		out[found++] = f;
		log_msg("lobby: found '%s' on %s (map %s, port %d, build %s, %d ms)", f.session.name, f.address,
			f.session.map, f.session.port, f.session.build, f.pingMs);
	}
	closesocket(s);
	return found;
}
