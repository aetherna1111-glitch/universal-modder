"""Account, token, profile, skin, friend and presence operations (no HTTP here)."""
from __future__ import annotations

import base64
import json
import logging
import sqlite3
import time
from dataclasses import dataclass

from . import security
from .config import Settings
from .security import RateLimited, ValidationError
from .signing import Signer
from .skins import Skin, TextureStore

log = logging.getLogger("luminary_auth")


class AuthError(Exception):
    """Invalid credentials or token. The message is generic on purpose."""


class Conflict(Exception):
    pass


class NotFound(Exception):
    pass


class Forbidden(Exception):
    pass


def now() -> int:
    return int(time.time())


# ---------------------------------------------------------------- users

def register(conn: sqlite3.Connection, settings: Settings, username: str, password: str, ip: str) -> sqlite3.Row:
    if not settings.registration_open:
        raise Forbidden("Регистрация сейчас закрыта")
    security.validate_username(username)
    security.validate_password(password, username)
    security.rate_limit(conn, f"register:ip:{ip}", limit=5, window=3600)
    lower = username.lower()
    if conn.execute("SELECT 1 FROM reserved_names WHERE name_lower = ?", (lower,)).fetchone():
        raise Conflict("Этот ник зарезервирован администрацией")
    pw_hash = security.hash_password(password)  # slow; do it outside the write transaction
    t = now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        if conn.execute("SELECT 1 FROM users WHERE username_lower = ?", (lower,)).fetchone() or \
                conn.execute("SELECT 1 FROM profiles WHERE name_lower = ?", (lower,)).fetchone():
            raise Conflict("Этот ник уже занят")
        cur = conn.execute(
            "INSERT INTO users(username, username_lower, password_hash, created_at, password_changed_at) "
            "VALUES (?, ?, ?, ?, ?)", (username, lower, pw_hash, t, t))
        user_id = cur.lastrowid
        conn.execute(
            "INSERT INTO profiles(uuid, user_id, name, name_lower) VALUES (?, ?, ?, ?)",
            (security.offline_uuid(username), user_id, username, lower))
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
    log.info("registered user %s", username)
    return get_user(conn, user_id)


def get_user(conn: sqlite3.Connection, user_id: int) -> sqlite3.Row:
    row = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if row is None:
        raise NotFound("Пользователь не найден")
    return row


def authenticate(conn: sqlite3.Connection, username: str, password: str, ip: str) -> sqlite3.Row:
    """Check credentials with brute-force and credential-stuffing limits.

    Limits: 20 attempts/10 min and 100/day per IP; 10 failures/15 min per account (any IP).
    Errors never reveal whether the account exists.
    """
    if not isinstance(username, str) or not isinstance(password, str) or len(password) > security.PASSWORD_MAX \
            or len(username) > 64:
        raise AuthError("Неверный ник или пароль")
    lower = username.lower()
    security.rate_limit(conn, f"login:ip:{ip}", limit=20, window=600)
    security.rate_limit(conn, f"login:ipday:{ip}", limit=100, window=86400)
    security.rate_limit(conn, f"loginfail:user:{lower}", limit=10, window=900, record=False)
    row = conn.execute("SELECT * FROM users WHERE username_lower = ?", (lower,)).fetchone()
    ok = security.verify_password(row["password_hash"] if row else None, password)
    if not ok or row is None or row["disabled"]:
        security.record_event(conn, f"loginfail:user:{lower}")
        log.info("failed login for %r from %s", username[:32], ip)
        raise AuthError("Неверный ник или пароль")
    security.clear_bucket(conn, f"loginfail:user:{lower}")
    if security.needs_rehash(row["password_hash"]):
        conn.execute("UPDATE users SET password_hash = ? WHERE id = ?", (security.hash_password(password), row["id"]))
    return row


def change_password(conn: sqlite3.Connection, user_id: int, old: str, new: str, keep_token_hash: str | None) -> None:
    user = get_user(conn, user_id)
    security.rate_limit(conn, f"pwchange:user:{user_id}", limit=10, window=3600)
    if not security.verify_password(user["password_hash"], old):
        raise AuthError("Текущий пароль неверен")
    security.validate_password(new, user["username"])
    conn.execute("UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?",
                 (security.hash_password(new), now(), user_id))
    # Sign out every other session and all game tokens.
    if keep_token_hash:
        conn.execute("DELETE FROM tokens WHERE user_id = ? AND token_hash <> ?", (user_id, keep_token_hash))
    else:
        conn.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
    log.info("password changed for user id %s", user_id)


def set_password_admin(conn: sqlite3.Connection, username: str, new: str) -> None:
    row = conn.execute("SELECT * FROM users WHERE username_lower = ?", (username.lower(),)).fetchone()
    if row is None:
        raise NotFound("Пользователь не найден")
    security.validate_password(new, row["username"])
    conn.execute("UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?",
                 (security.hash_password(new), now(), row["id"]))
    conn.execute("DELETE FROM tokens WHERE user_id = ?", (row["id"],))


# ---------------------------------------------------------------- tokens

def issue_token(conn: sqlite3.Connection, user_id: int, kind: str, ttl: int,
                client_token: str | None = None, parent_hash: str | None = None) -> tuple[str, int]:
    token = security.new_token()
    t = now()
    expires = t + ttl
    conn.execute(
        "INSERT INTO tokens(token_hash, user_id, kind, client_token, parent_hash, created_at, expires_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        (security.token_hash(token), user_id, kind, client_token, parent_hash, t, expires))
    # Opportunistic cleanup of expired tokens and join records.
    conn.execute("DELETE FROM tokens WHERE expires_at < ?", (t,))
    return token, expires


@dataclass
class TokenInfo:
    token_hash: str
    user: sqlite3.Row
    kind: str
    client_token: str | None
    expires_at: int


def resolve_token(conn: sqlite3.Connection, token: str | None, kind: str) -> TokenInfo:
    if not token or not isinstance(token, str) or len(token) > 256:
        raise AuthError("Сессия недействительна, войди заново")
    th = security.token_hash(token)
    row = conn.execute(
        "SELECT t.*, u.disabled FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?",
        (th,)).fetchone()
    if row is None or row["kind"] != kind or row["expires_at"] < now() or row["disabled"]:
        raise AuthError("Сессия недействительна, войди заново")
    conn.execute("UPDATE tokens SET last_used_at = ? WHERE token_hash = ?", (now(), th))
    return TokenInfo(th, get_user(conn, row["user_id"]), row["kind"], row["client_token"], row["expires_at"])


def revoke_token(conn: sqlite3.Connection, th: str) -> None:
    conn.execute("DELETE FROM tokens WHERE token_hash = ? OR parent_hash = ?", (th, th))


def revoke_all(conn: sqlite3.Connection, user_id: int, kind: str | None = None) -> None:
    if kind:
        conn.execute("DELETE FROM tokens WHERE user_id = ? AND kind = ?", (user_id, kind))
    else:
        conn.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))


# ---------------------------------------------------------------- profiles & textures

def profile_for_user(conn: sqlite3.Connection, user_id: int) -> sqlite3.Row:
    row = conn.execute("SELECT * FROM profiles WHERE user_id = ?", (user_id,)).fetchone()
    if row is None:
        raise NotFound("Профиль не найден")
    return row


def profile_by_uuid(conn: sqlite3.Connection, uuid_hex: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT p.* FROM profiles p JOIN users u ON u.id = p.user_id WHERE p.uuid = ? AND u.disabled = 0",
        (uuid_hex,)).fetchone()


def profile_by_name(conn: sqlite3.Connection, name: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT p.* FROM profiles p JOIN users u ON u.id = p.user_id WHERE p.name_lower = ? AND u.disabled = 0",
        (name.lower(),)).fetchone()


def texture_url(settings: Settings, sha256: str) -> str:
    return f"{settings.public_url}/textures/{sha256}"


def textures_payload(settings: Settings, profile: sqlite3.Row) -> dict:
    textures: dict = {}
    if profile["skin_hash"]:
        skin: dict = {"url": texture_url(settings, profile["skin_hash"])}
        if profile["skin_model"] == "slim":
            skin["metadata"] = {"model": "slim"}
        textures["SKIN"] = skin
    return {
        "timestamp": int(time.time() * 1000),
        "profileId": profile["uuid"],
        "profileName": profile["name"],
        "signatureRequired": True,
        "textures": textures,
    }


def profile_json(settings: Settings, signer: Signer, profile: sqlite3.Row, signed: bool) -> dict:
    value = base64.b64encode(json.dumps(textures_payload(settings, profile), separators=(",", ":"))
                             .encode("utf-8")).decode("ascii")
    prop: dict = {"name": "textures", "value": value}
    if signed:
        prop["signature"] = signer.sign(value)
    uploadable = {"name": "uploadableTextures", "value": "skin"}
    if signed:
        uploadable["signature"] = signer.sign(uploadable["value"])
    return {"id": profile["uuid"], "name": profile["name"], "properties": [prop, uploadable]}


def set_skin(conn: sqlite3.Connection, store: TextureStore, user_id: int, skin: Skin, model: str) -> sqlite3.Row:
    if model not in ("classic", "slim"):
        raise ValidationError("Модель должна быть classic или slim")
    security.rate_limit(conn, f"skin:user:{user_id}", limit=20, window=3600)
    store.save(skin)
    profile = profile_for_user(conn, user_id)
    old = profile["skin_hash"]
    conn.execute("BEGIN IMMEDIATE")
    try:
        conn.execute("INSERT OR IGNORE INTO textures(hash, width, height, size, created_at) VALUES (?, ?, ?, ?, ?)",
                     (skin.sha256, skin.width, skin.height, len(skin.png), now()))
        conn.execute("UPDATE profiles SET skin_hash = ?, skin_model = ?, skin_updated_at = ? WHERE user_id = ?",
                     (skin.sha256, model, now(), user_id))
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
    if old and old != skin.sha256:
        _gc_texture(conn, store, old)
    log.info("skin updated for user id %s", user_id)
    return profile_for_user(conn, user_id)


def set_skin_model(conn: sqlite3.Connection, user_id: int, model: str) -> sqlite3.Row:
    if model not in ("classic", "slim"):
        raise ValidationError("Модель должна быть classic или slim")
    profile = profile_for_user(conn, user_id)
    if not profile["skin_hash"]:
        raise NotFound("Сначала загрузи скин")
    conn.execute("UPDATE profiles SET skin_model = ?, skin_updated_at = ? WHERE user_id = ?", (model, now(), user_id))
    return profile_for_user(conn, user_id)


def clear_skin(conn: sqlite3.Connection, store: TextureStore, user_id: int) -> sqlite3.Row:
    profile = profile_for_user(conn, user_id)
    old = profile["skin_hash"]
    conn.execute("UPDATE profiles SET skin_hash = NULL, skin_model = 'classic', skin_updated_at = ? WHERE user_id = ?",
                 (now(), user_id))
    if old:
        _gc_texture(conn, store, old)
    return profile_for_user(conn, user_id)


def _gc_texture(conn: sqlite3.Connection, store: TextureStore, sha256: str) -> None:
    if conn.execute("SELECT 1 FROM profiles WHERE skin_hash = ?", (sha256,)).fetchone():
        return
    conn.execute("DELETE FROM textures WHERE hash = ?", (sha256,))
    store.delete(sha256)


# ---------------------------------------------------------------- yggdrasil joins

def record_join(conn: sqlite3.Connection, settings: Settings, server_id: str, profile_uuid: str, ip: str) -> None:
    t = now()
    conn.execute("DELETE FROM joins WHERE created_at < ?", (t - settings.join_ttl,))
    conn.execute("INSERT OR REPLACE INTO joins(server_id, profile_uuid, ip, created_at) VALUES (?, ?, ?, ?)",
                 (server_id, profile_uuid, ip, t))


def consume_join(conn: sqlite3.Connection, settings: Settings, username: str, server_id: str,
                 ip: str | None) -> sqlite3.Row | None:
    profile = profile_by_name(conn, username)
    if profile is None:
        return None
    row = conn.execute("SELECT * FROM joins WHERE server_id = ? AND profile_uuid = ?",
                       (server_id, profile["uuid"])).fetchone()
    if row is None or row["created_at"] < now() - settings.join_ttl:
        return None
    if ip and row["ip"] and ip != row["ip"]:
        return None
    conn.execute("DELETE FROM joins WHERE server_id = ? AND profile_uuid = ?", (server_id, profile["uuid"]))
    set_presence(conn, profile["user_id"], "playing")
    return profile


# ---------------------------------------------------------------- presence

PRESENCE_STATES = {"online", "playing"}


def set_presence(conn: sqlite3.Connection, user_id: int, state: str) -> None:
    if state not in PRESENCE_STATES:
        raise ValidationError("Неизвестный статус")
    conn.execute("INSERT INTO presence(user_id, state, updated_at) VALUES (?, ?, ?) "
                 "ON CONFLICT(user_id) DO UPDATE SET state = excluded.state, updated_at = excluded.updated_at",
                 (user_id, state, now()))


def clear_presence(conn: sqlite3.Connection, user_id: int) -> None:
    conn.execute("DELETE FROM presence WHERE user_id = ?", (user_id,))


def presence_of(conn: sqlite3.Connection, settings: Settings, user_id: int) -> str:
    row = conn.execute("SELECT state, updated_at FROM presence WHERE user_id = ?", (user_id,)).fetchone()
    if row is None or row["updated_at"] < now() - settings.presence_ttl:
        return "offline"
    return row["state"]


# ---------------------------------------------------------------- friends

def search_users(conn: sqlite3.Connection, me: int, query: str, limit: int = 20) -> list[sqlite3.Row]:
    q = (query or "").strip()
    if len(q) < 2 or len(q) > 16 or not all(c.isalnum() or c == "_" for c in q):
        raise ValidationError("Введи от 2 до 16 символов ника")
    security.rate_limit(conn, f"search:user:{me}", limit=60, window=60)
    like = q.lower().replace("_", r"\_") + "%"
    return conn.execute(
        "SELECT p.uuid, p.name, p.skin_hash, p.skin_model, u.id AS user_id FROM profiles p "
        "JOIN users u ON u.id = p.user_id WHERE p.name_lower LIKE ? ESCAPE '\\' AND u.disabled = 0 AND u.id <> ? "
        "ORDER BY length(p.name), p.name_lower LIMIT ?", (like, me, limit)).fetchall()


def _user_id_by_name(conn: sqlite3.Connection, name: str) -> int:
    p = profile_by_name(conn, name or "")
    if p is None:
        raise NotFound("Игрок не найден")
    return p["user_id"]


def send_friend_request(conn: sqlite3.Connection, me: int, target_name: str) -> str:
    target = _user_id_by_name(conn, target_name)
    if target == me:
        raise ValidationError("Нельзя добавить в друзья самого себя")
    security.rate_limit(conn, f"friendreq:user:{me}", limit=30, window=3600)
    row = conn.execute(
        "SELECT * FROM friendships WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)",
        (me, target, target, me)).fetchone()
    if row is not None:
        if row["status"] == "accepted":
            raise Conflict("Вы уже друзья")
        if row["requester_id"] == me:
            raise Conflict("Заявка уже отправлена")
        # They already asked us: accept.
        conn.execute("UPDATE friendships SET status = 'accepted', responded_at = ? WHERE id = ?", (now(), row["id"]))
        return "accepted"
    conn.execute("INSERT INTO friendships(requester_id, addressee_id, status, created_at) VALUES (?, ?, 'pending', ?)",
                 (me, target, now()))
    return "pending"


def respond_friend_request(conn: sqlite3.Connection, me: int, request_id: int, accept: bool) -> None:
    row = conn.execute("SELECT * FROM friendships WHERE id = ?", (request_id,)).fetchone()
    # Only the addressee may answer; anything else looks like "not found" to avoid leaking ids.
    if row is None or row["addressee_id"] != me or row["status"] != "pending":
        raise NotFound("Заявка не найдена")
    if accept:
        conn.execute("UPDATE friendships SET status = 'accepted', responded_at = ? WHERE id = ?", (now(), request_id))
    else:
        conn.execute("DELETE FROM friendships WHERE id = ?", (request_id,))


def cancel_friend_request(conn: sqlite3.Connection, me: int, request_id: int) -> None:
    row = conn.execute("SELECT * FROM friendships WHERE id = ?", (request_id,)).fetchone()
    if row is None or row["requester_id"] != me or row["status"] != "pending":
        raise NotFound("Заявка не найдена")
    conn.execute("DELETE FROM friendships WHERE id = ?", (request_id,))


def remove_friend(conn: sqlite3.Connection, me: int, name: str) -> None:
    other = _user_id_by_name(conn, name)
    cur = conn.execute(
        "DELETE FROM friendships WHERE status = 'accepted' AND ((requester_id = ? AND addressee_id = ?) "
        "OR (requester_id = ? AND addressee_id = ?))", (me, other, other, me))
    if cur.rowcount == 0:
        raise NotFound("Этот игрок не в списке друзей")


def are_friends(conn: sqlite3.Connection, a: int, b: int) -> bool:
    return conn.execute(
        "SELECT 1 FROM friendships WHERE status = 'accepted' AND ((requester_id = ? AND addressee_id = ?) "
        "OR (requester_id = ? AND addressee_id = ?))", (a, b, b, a)).fetchone() is not None


def friends_overview(conn: sqlite3.Connection, settings: Settings, me: int) -> dict:
    rows = conn.execute(
        "SELECT f.*, CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END AS other_id "
        "FROM friendships f WHERE f.requester_id = ? OR f.addressee_id = ?", (me, me, me)).fetchall()
    friends, incoming, outgoing = [], [], []
    for r in rows:
        p = conn.execute("SELECT p.uuid, p.name, p.skin_hash, p.skin_model FROM profiles p JOIN users u ON u.id = p.user_id "
                         "WHERE p.user_id = ? AND u.disabled = 0", (r["other_id"],)).fetchone()
        if p is None:
            continue
        item = {"id": r["id"], "name": p["name"], "uuid": p["uuid"],
                "skin_url": texture_url(settings, p["skin_hash"]) if p["skin_hash"] else None,
                "skin_model": p["skin_model"]}
        if r["status"] == "accepted":
            item["presence"] = presence_of(conn, settings, r["other_id"])
            friends.append(item)
        elif r["addressee_id"] == me:
            incoming.append(item)
        else:
            outgoing.append(item)
    order = {"playing": 0, "online": 1, "offline": 2}
    friends.sort(key=lambda f: (order[f["presence"]], f["name"].lower()))
    return {"friends": friends, "incoming": incoming, "outgoing": outgoing}


__all__ = ["AuthError", "Conflict", "NotFound", "Forbidden", "RateLimited", "ValidationError"]
