"""Yggdrasil API for authlib-injector (https://github.com/yushijinhun/authlib-injector/wiki).

authlib-injector rewrites Mojang hosts to this API root (<public_url>/yggdrasil):
  authserver.mojang.com      -> /authserver/...
  sessionserver.mojang.com   -> /sessionserver/...
  api.mojang.com             -> /api/...
  api.minecraftservices.com  -> /minecraftservices/...
The server (online-mode=true + authlib-injector) verifies every join with /hasJoined, so a player can
only enter with a name they hold a valid token for; skins are delivered as signed textures properties.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import __version__, accounts, security
from .config import Settings
from .deps import client_ip, get_conn, get_settings

router = APIRouter(prefix="/yggdrasil")


class YggdrasilError(Exception):
    def __init__(self, status: int, error: str, message: str):
        super().__init__(message)
        self.status, self.error, self.message = status, error, message


def forbidden(message: str = "Invalid token.") -> YggdrasilError:
    return YggdrasilError(403, "ForbiddenOperationException", message)


def error_response(exc: YggdrasilError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"error": exc.error, "errorMessage": exc.message})


class Agent(BaseModel):
    name: str = "Minecraft"
    version: int = 1


class AuthenticateBody(BaseModel):
    username: str = Field(max_length=64)
    password: str = Field(max_length=256)
    clientToken: str | None = Field(default=None, max_length=128)
    requestUser: bool = False
    agent: Agent | None = None


class TokenBody(BaseModel):
    accessToken: str = Field(max_length=256)
    clientToken: str | None = Field(default=None, max_length=128)
    requestUser: bool = False
    selectedProfile: dict | None = None


class SignoutBody(BaseModel):
    username: str = Field(max_length=64)
    password: str = Field(max_length=256)


class JoinBody(BaseModel):
    accessToken: str = Field(max_length=256)
    selectedProfile: str = Field(max_length=36)
    serverId: str = Field(max_length=64)


def _profile_brief(p) -> dict:
    return {"id": p["uuid"], "name": p["name"]}


@router.get("")
@router.get("/")
def metadata(request: Request, settings: Settings = Depends(get_settings)):
    signer = request.app.state.signer
    return {
        "meta": {
            "serverName": settings.server_name,
            "implementationName": "luminary-auth",
            "implementationVersion": __version__,
            "links": {"homepage": settings.public_url},
            "feature.non_email_login": True,
            "feature.no_mojang_namespace": True,   # Mojang-account names cannot be used here
            "feature.username_check": True,
            "feature.enable_profile_key": False,   # no chat signing keys; use enforce-secure-profile=false
            "feature.enable_mojang_anti_features": False,
        },
        "skinDomains": settings.skin_domains,
        "signaturePublickey": signer.public_pem(),
    }


@router.post("/authserver/authenticate")
def authenticate(body: AuthenticateBody, request: Request, conn=Depends(get_conn),
                 settings: Settings = Depends(get_settings)):
    """For third-party authlib-injector launchers; the Luminary launcher uses /api/v1/game/token."""
    try:
        user = accounts.authenticate(conn, body.username, body.password, client_ip(request))
    except accounts.AuthError:
        raise forbidden("Invalid credentials. Invalid username or password.")
    profile = accounts.profile_for_user(conn, user["id"])
    client_token = body.clientToken or security.new_token()[:32]
    token, _ = accounts.issue_token(conn, user["id"], "game", settings.game_token_ttl, client_token=client_token)
    out = {"accessToken": token, "clientToken": client_token,
           "availableProfiles": [_profile_brief(profile)], "selectedProfile": _profile_brief(profile)}
    if body.requestUser:
        out["user"] = {"id": security.offline_uuid("user:" + user["username"]), "properties": []}
    return out


def _game_token(conn, body: TokenBody) -> accounts.TokenInfo:
    try:
        info = accounts.resolve_token(conn, body.accessToken, "game")
    except accounts.AuthError:
        raise forbidden()
    if body.clientToken and info.client_token and body.clientToken != info.client_token:
        raise forbidden()
    return info


@router.post("/authserver/refresh")
def refresh(body: TokenBody, conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    info = _game_token(conn, body)
    profile = accounts.profile_for_user(conn, info.user["id"])
    if body.selectedProfile and security.normalize_uuid(str(body.selectedProfile.get("id", ""))) != profile["uuid"]:
        raise YggdrasilError(400, "IllegalArgumentException", "Profile does not belong to this token.")
    parent = conn.execute("SELECT parent_hash FROM tokens WHERE token_hash = ?", (info.token_hash,)).fetchone()
    accounts.revoke_token(conn, info.token_hash)
    token, _ = accounts.issue_token(conn, info.user["id"], "game", settings.game_token_ttl,
                                    client_token=info.client_token, parent_hash=parent["parent_hash"] if parent else None)
    out = {"accessToken": token, "clientToken": info.client_token or "", "selectedProfile": _profile_brief(profile)}
    if body.requestUser:
        out["user"] = {"id": security.offline_uuid("user:" + info.user["username"]), "properties": []}
    return out


@router.post("/authserver/validate", status_code=204)
def validate(body: TokenBody, conn=Depends(get_conn)):
    _game_token(conn, body)
    return Response(status_code=204)


@router.post("/authserver/invalidate", status_code=204)
def invalidate(body: TokenBody, conn=Depends(get_conn)):
    conn.execute("DELETE FROM tokens WHERE token_hash = ? AND kind = 'game'", (security.token_hash(body.accessToken),))
    return Response(status_code=204)


@router.post("/authserver/signout", status_code=204)
def signout(body: SignoutBody, request: Request, conn=Depends(get_conn)):
    try:
        user = accounts.authenticate(conn, body.username, body.password, client_ip(request))
    except accounts.AuthError:
        raise forbidden("Invalid credentials. Invalid username or password.")
    accounts.revoke_all(conn, user["id"], "game")
    return Response(status_code=204)


@router.post("/sessionserver/session/minecraft/join", status_code=204)
def join(body: JoinBody, request: Request, conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    ip = client_ip(request)
    security.rate_limit(conn, f"join:ip:{ip}", limit=60, window=60)
    try:
        info = accounts.resolve_token(conn, body.accessToken, "game")
    except accounts.AuthError:
        raise forbidden()
    profile = accounts.profile_for_user(conn, info.user["id"])
    if security.normalize_uuid(body.selectedProfile) != profile["uuid"]:
        raise forbidden("Invalid profile.")
    accounts.record_join(conn, settings, body.serverId, profile["uuid"], ip)
    return Response(status_code=204)


@router.get("/sessionserver/session/minecraft/hasJoined")
def has_joined(request: Request, username: str = Query(max_length=16), serverId: str = Query(max_length=64),
               ip: str | None = Query(default=None, max_length=64),
               conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    profile = accounts.consume_join(conn, settings, username, serverId, ip)
    if profile is None:
        return Response(status_code=204)
    return accounts.profile_json(settings, request.app.state.signer, profile, signed=True)


@router.get("/sessionserver/session/minecraft/profile/{uuid}")
def profile(uuid: str, request: Request, unsigned: bool = Query(True), conn=Depends(get_conn),
            settings: Settings = Depends(get_settings)):
    norm = security.normalize_uuid(uuid)
    p = accounts.profile_by_uuid(conn, norm) if norm else None
    if p is None:
        return Response(status_code=204)
    return accounts.profile_json(settings, request.app.state.signer, p, signed=not unsigned)


@router.post("/api/profiles/minecraft")
async def bulk_profiles(request: Request, conn=Depends(get_conn)):
    try:
        names = await request.json()
    except Exception:
        raise YggdrasilError(400, "IllegalArgumentException", "Body must be a JSON array of names.")
    if not isinstance(names, list) or len(names) > 100 or not all(isinstance(n, str) for n in names):
        raise YggdrasilError(400, "IllegalArgumentException", "Body must be a JSON array of at most 100 names.")
    out, seen = [], set()
    for n in names:
        if n.lower() in seen or len(n) > 16:
            continue
        seen.add(n.lower())
        p = accounts.profile_by_name(conn, n)
        if p is not None:
            out.append(_profile_brief(p))
    return out


@router.get("/api/users/profiles/minecraft/{name}")
def profile_by_name(name: str, conn=Depends(get_conn)):
    p = accounts.profile_by_name(conn, name[:16])
    if p is None:
        return Response(status_code=204)
    return _profile_brief(p)


@router.get("/minecraftservices/player/attributes")
def player_attributes():
    return {
        "privileges": {
            "onlineChat": {"enabled": True},
            "multiplayerServer": {"enabled": True},
            "multiplayerRealms": {"enabled": False},
            "telemetry": {"enabled": False},
        },
        "profanityFilterPreferences": {"profanityFilterOn": False},
    }


@router.get("/minecraftservices/privacy/blocklist")
def blocklist():
    return {"blockedProfiles": []}
