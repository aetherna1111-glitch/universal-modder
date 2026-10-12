"""JSON API used by the Luminary launcher (prefix /api/v1). All writes require a session token."""
from __future__ import annotations

import sqlite3
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from pydantic import BaseModel, Field

from . import accounts, security
from .accounts import TokenInfo
from .config import Settings
from .deps import client_ip, current_session, get_conn, get_settings
from .skins import SkinError, TextureStore, validate_skin

router = APIRouter(prefix="/api/v1")


class Credentials(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class PasswordChange(BaseModel):
    old_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=1, max_length=256)


class ModelChange(BaseModel):
    model: Literal["classic", "slim"]


class FriendRequest(BaseModel):
    username: str = Field(min_length=1, max_length=16)


class Presence(BaseModel):
    state: Literal["online", "playing"]


def _me(conn: sqlite3.Connection, settings: Settings, info: TokenInfo) -> dict:
    user = info.user
    profile = accounts.profile_for_user(conn, user["id"])
    return {
        "username": user["username"],
        "created_at": user["created_at"],
        "session_expires_at": info.expires_at,
        "profile": {
            "uuid": profile["uuid"],
            "name": profile["name"],
            "skin": {
                "url": accounts.texture_url(settings, profile["skin_hash"]) if profile["skin_hash"] else None,
                "hash": profile["skin_hash"],
                "model": profile["skin_model"],
                "updated_at": profile["skin_updated_at"],
            },
        },
    }


def _session_response(conn, settings, user_id: int, token: str, expires: int) -> dict:
    info = accounts.resolve_token(conn, token, "session")
    body = _me(conn, settings, info)
    body.update({"access_token": token, "expires_at": expires})
    return body


@router.get("/health")
def health():
    return {"status": "ok"}


@router.get("/info")
def info(settings: Settings = Depends(get_settings)):
    return {
        "server_name": settings.server_name,
        "registration_open": settings.registration_open,
        "yggdrasil_url": f"{settings.public_url}/yggdrasil",
        "skin": {"max_bytes": settings.max_skin_bytes, "sizes": ["64x64", "64x32"], "models": ["classic", "slim"]},
    }


@router.post("/auth/register", status_code=201)
def register(body: Credentials, request: Request, conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    user = accounts.register(conn, settings, body.username, body.password, client_ip(request))
    token, expires = accounts.issue_token(conn, user["id"], "session", settings.session_ttl)
    return _session_response(conn, settings, user["id"], token, expires)


@router.post("/auth/login")
def login(body: Credentials, request: Request, conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    user = accounts.authenticate(conn, body.username, body.password, client_ip(request))
    token, expires = accounts.issue_token(conn, user["id"], "session", settings.session_ttl)
    return _session_response(conn, settings, user["id"], token, expires)


@router.post("/auth/refresh")
def refresh(info: TokenInfo = Depends(current_session), conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    accounts.revoke_token(conn, info.token_hash)
    token, expires = accounts.issue_token(conn, info.user["id"], "session", settings.session_ttl)
    return _session_response(conn, settings, info.user["id"], token, expires)


@router.post("/auth/logout", status_code=204)
def logout(info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.revoke_token(conn, info.token_hash)  # also revokes game tokens issued from this session
    accounts.clear_presence(conn, info.user["id"])
    return Response(status_code=204)


@router.post("/auth/logout-all", status_code=204)
def logout_all(info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.revoke_all(conn, info.user["id"])
    accounts.clear_presence(conn, info.user["id"])
    return Response(status_code=204)


@router.post("/auth/password", status_code=204)
def password(body: PasswordChange, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.change_password(conn, info.user["id"], body.old_password, body.new_password, info.token_hash)
    return Response(status_code=204)


@router.get("/me")
def me(info: TokenInfo = Depends(current_session), conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    return _me(conn, settings, info)


@router.post("/game/token")
def game_token(info: TokenInfo = Depends(current_session), conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    """Short-lived token for the game process. It can only join servers, not manage the account."""
    profile = accounts.profile_for_user(conn, info.user["id"])
    client_token = security.new_token()[:32]
    token, expires = accounts.issue_token(conn, info.user["id"], "game", settings.game_token_ttl,
                                          client_token=client_token, parent_hash=info.token_hash)
    return {"access_token": token, "client_token": client_token, "expires_at": expires,
            "uuid": profile["uuid"], "name": profile["name"],
            "yggdrasil_url": f"{settings.public_url}/yggdrasil"}


async def _read_limited(request: Request, limit: int) -> bytes:
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > limit:
                raise SkinError(f"Файл больше {limit // 1024} КБ")
        except ValueError:
            raise SkinError("Неверный заголовок Content-Length")
    chunks, total = [], 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > limit:
            raise SkinError(f"Файл больше {limit // 1024} КБ")
        chunks.append(chunk)
    return b"".join(chunks)


@router.put("/me/skin")
async def upload_skin(request: Request, model: Literal["classic", "slim", "auto"] = Query("auto"),
                      info: TokenInfo = Depends(current_session), conn=Depends(get_conn),
                      settings: Settings = Depends(get_settings)):
    data = await _read_limited(request, settings.max_skin_bytes)
    skin = validate_skin(data, settings.max_skin_bytes)
    chosen = skin.suggested_model if model == "auto" else model
    store: TextureStore = request.app.state.textures
    accounts.set_skin(conn, store, info.user["id"], skin, chosen)
    return _me(conn, settings, info)


@router.patch("/me/skin")
def change_model(body: ModelChange, info: TokenInfo = Depends(current_session), conn=Depends(get_conn),
                 settings: Settings = Depends(get_settings)):
    accounts.set_skin_model(conn, info.user["id"], body.model)
    return _me(conn, settings, info)


@router.delete("/me/skin")
def delete_skin(request: Request, info: TokenInfo = Depends(current_session), conn=Depends(get_conn),
                settings: Settings = Depends(get_settings)):
    accounts.clear_skin(conn, request.app.state.textures, info.user["id"])
    return _me(conn, settings, info)


@router.post("/presence", status_code=204)
def presence(body: Presence, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.set_presence(conn, info.user["id"], body.state)
    return Response(status_code=204)


@router.get("/users/search")
def search(q: str = Query(..., max_length=16), info: TokenInfo = Depends(current_session), conn=Depends(get_conn),
           settings: Settings = Depends(get_settings)):
    rows = accounts.search_users(conn, info.user["id"], q)
    return {"users": [{"name": r["name"], "uuid": r["uuid"]} for r in rows]}


@router.get("/users/{name}")
def public_profile(name: str, info: TokenInfo = Depends(current_session), conn=Depends(get_conn),
                   settings: Settings = Depends(get_settings)):
    p = accounts.profile_by_name(conn, name[:16])
    if p is None:
        raise accounts.NotFound("Игрок не найден")
    friend = accounts.are_friends(conn, info.user["id"], p["user_id"])
    body = {"name": p["name"], "uuid": p["uuid"], "friend": friend,
            "skin_url": accounts.texture_url(settings, p["skin_hash"]) if p["skin_hash"] else None,
            "skin_model": p["skin_model"]}
    if friend:  # presence is only visible to friends
        body["presence"] = accounts.presence_of(conn, settings, p["user_id"])
    return body


@router.get("/friends")
def friends(info: TokenInfo = Depends(current_session), conn=Depends(get_conn), settings: Settings = Depends(get_settings)):
    return accounts.friends_overview(conn, settings, info.user["id"])


@router.post("/friends/requests")
def friend_request(body: FriendRequest, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    return {"status": accounts.send_friend_request(conn, info.user["id"], body.username)}


@router.post("/friends/requests/{request_id}/accept", status_code=204)
def accept(request_id: int, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.respond_friend_request(conn, info.user["id"], request_id, True)
    return Response(status_code=204)


@router.post("/friends/requests/{request_id}/decline", status_code=204)
def decline(request_id: int, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.respond_friend_request(conn, info.user["id"], request_id, False)
    return Response(status_code=204)


@router.delete("/friends/requests/{request_id}", status_code=204)
def cancel(request_id: int, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.cancel_friend_request(conn, info.user["id"], request_id)
    return Response(status_code=204)


@router.delete("/friends/{name}", status_code=204)
def unfriend(name: str, info: TokenInfo = Depends(current_session), conn=Depends(get_conn)):
    accounts.remove_friend(conn, info.user["id"], name[:16])
    return Response(status_code=204)


@router.get("/news")
def news(limit: int = Query(5, ge=1, le=20), conn=Depends(get_conn)):
    rows = conn.execute("SELECT id, title, body, published_at FROM news ORDER BY published_at DESC LIMIT ?",
                        (limit,)).fetchall()
    return {"news": [dict(r) for r in rows]}
