/*
 * FarCryCoop: launch request parser. See CoopArgs.h.
 */
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "CoopArgs.h"

namespace
{
	enum ECharset { CS_MAP, CS_ADDRESS, CS_NAME, CS_DIGITS };

	bool Allowed(char c, ECharset cs)
	{
		bool alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
		switch (cs)
		{
		case CS_MAP:     return alnum || c == '_' || c == '-';
		case CS_ADDRESS: return alnum || c == '.' || c == '-' || c == ':';
		case CS_NAME:    return alnum || c == '_' || c == '-' || c == ' ' || c == '.' || c == '!' || c == '#';
		case CS_DIGITS:  return c >= '0' && c <= '9';
		}
		return false;
	}

	// Copies one token value (up to the next unquoted space) into out. Handles "-coopname:My Server" when
	// the whole argument was quoted by the launcher ("-coopname:My Server").
	const char *ReadValue(const char *p, char *out, size_t outSize, bool *tooLong, bool quoted)
	{
		size_t n = 0;
		*tooLong = false;
		while (*p)
		{
			if (*p == '"') { quoted = !quoted; ++p; continue; }
			if (!quoted && (*p == ' ' || *p == '\t'))
				break;
			if (n + 1 < outSize)
				out[n++] = *p;
			else
				*tooLong = true;
			++p;
		}
		out[n] = 0;
		return p;
	}

	bool Valid(const char *s, ECharset cs, bool allowEmpty)
	{
		if (!*s)
			return allowEmpty;
		for (; *s; ++s)
			if (!Allowed(*s, cs))
				return false;
		return true;
	}

	int Fail(SCoopLaunchRequest *req, const char *what, const char *value)
	{
		req->type = COOP_REQ_NONE;
		snprintf(req->error, sizeof(req->error), "invalid %s '%.60s'", what, value);
		return -1;
	}

	bool StartsWithI(const char *s, const char *prefix)
	{
		for (; *prefix; ++s, ++prefix)
		{
			char a = *s, b = *prefix;
			if (a >= 'A' && a <= 'Z') a = (char)(a - 'A' + 'a');
			if (b >= 'A' && b <= 'Z') b = (char)(b - 'A' + 'a');
			if (a != b)
				return false;
		}
		return true;
	}
}

extern "C" int Coop_ParseLaunchArgs(const char *cmdline, SCoopLaunchRequest *req)
{
	memset(req, 0, sizeof(*req));
	if (!cmdline)
		return 0;

	int requests = 0;
	const char *p = cmdline;
	while (*p)
	{
		// Arguments may come quoted as a whole: "-coopname:My Server".
		const char *arg = p;
		if (*arg == '"')
			++arg;
		if (*arg != '-' || !StartsWithI(arg, "-coop"))
		{
			// skip this token
			bool quoted = false;
			while (*p && (quoted || (*p != ' ' && *p != '\t')))
			{
				if (*p == '"') quoted = !quoted;
				++p;
			}
			while (*p == ' ' || *p == '\t') ++p;
			continue;
		}

		static const char *keys[] = { "-coophost:", "-coopjoin:", "-coopname:", "-coopmax:", "-coopport:", "-cooppass:" };
		int key = -1;
		for (int i = 0; i < 6; ++i)
			if (StartsWithI(arg, keys[i])) { key = i; break; }
		if (key < 0)
		{
			char unknown[COOP_ARG_MAX];
			bool tooLong;
			ReadValue(arg, unknown, sizeof(unknown), &tooLong, arg != p);
			return Fail(req, "argument", unknown);
		}

		char value[COOP_ARG_MAX * 2];
		bool tooLong;
		p = ReadValue(arg + strlen(keys[key]), value, sizeof(value), &tooLong, arg != p);
		while (*p == ' ' || *p == '\t') ++p;
		if (tooLong || strlen(value) >= COOP_ARG_MAX)
			return Fail(req, "length of", keys[key]);

		switch (key)
		{
		case 0: // host
		{
			char *colon = strchr(value, ':');
			if (colon)
			{
				*colon = 0;
				if (!Valid(colon + 1, CS_MAP, false))
					return Fail(req, "mission", colon + 1);
				strcpy(req->mission, colon + 1);
			}
			if (!Valid(value, CS_MAP, false))
				return Fail(req, "map", value);
			strcpy(req->map, value);
			req->type = COOP_REQ_HOST;
			++requests;
			break;
		}
		case 1: // join
			if (!Valid(value, CS_ADDRESS, false))
				return Fail(req, "address", value);
			strcpy(req->address, value);
			req->type = COOP_REQ_JOIN;
			++requests;
			break;
		case 2:
			if (!Valid(value, CS_NAME, false))
				return Fail(req, "server name", value);
			strcpy(req->name, value);
			break;
		case 3:
			if (!Valid(value, CS_DIGITS, false) || atoi(value) < 2 || atoi(value) > 16)
				return Fail(req, "max players", value);
			req->maxPlayers = atoi(value);
			break;
		case 4:
			if (!Valid(value, CS_DIGITS, false) || strlen(value) > 5 || atoi(value) < 1024 || atoi(value) > 65535)
				return Fail(req, "port", value);
			req->port = atoi(value);
			break;
		case 5:
			if (!Valid(value, CS_MAP, false))
				return Fail(req, "password", value);
			strcpy(req->password, value);
			break;
		}
	}

	if (requests > 1)
	{
		req->type = COOP_REQ_NONE;
		snprintf(req->error, sizeof(req->error), "both -coophost and -coopjoin given");
		return -1;
	}
	return req->type != COOP_REQ_NONE ? 1 : 0;
}
