"""Password hashing, opaque tokens, rate limiting and input validation."""
from __future__ import annotations

import hashlib
import re
import secrets
import sqlite3
import time
import uuid

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

# Argon2id with the RFC 9106 "second recommended option" (64 MiB, t=3, p=4) - argon2-cffi's defaults.
_hasher = PasswordHasher()
# Verified against unknown usernames so a miss costs the same time as a wrong password.
_DUMMY_HASH = _hasher.hash(secrets.token_urlsafe(16))

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,16}$")
PASSWORD_MIN = 8
PASSWORD_MAX = 128
_COMMON_PASSWORDS = {
    "12345678", "123456789", "1234567890", "password", "password1", "qwerty123", "qwertyuiop",
    "11111111", "00000000", "iloveyou", "minecraft", "minecraft1", "luminary", "luminary1",
    "йцукенгш", "пароль123", "qwerty12", "asdfghjk", "abc12345", "1q2w3e4r",
}


class ValidationError(ValueError):
    """User-facing validation problem (message is safe to show)."""


def validate_username(name: str) -> str:
    if not isinstance(name, str) or not USERNAME_RE.fullmatch(name):
        raise ValidationError("Ник: 3-16 символов, только латиница, цифры и _")
    return name


def validate_password(password: str, username: str = "") -> str:
    if not isinstance(password, str):
        raise ValidationError("Пароль обязателен")
    if len(password) < PASSWORD_MIN:
        raise ValidationError(f"Пароль должен быть не короче {PASSWORD_MIN} символов")
    if len(password) > PASSWORD_MAX:
        raise ValidationError(f"Пароль должен быть не длиннее {PASSWORD_MAX} символов")
    if username and password.lower() == username.lower():
        raise ValidationError("Пароль не должен совпадать с ником")
    if password.lower() in _COMMON_PASSWORDS:
        raise ValidationError("Этот пароль слишком распространён, выбери другой")
    return password


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(stored_hash: str | None, password: str) -> bool:
    """Constant-work verification; pass stored_hash=None for unknown users."""
    try:
        return _hasher.verify(stored_hash or _DUMMY_HASH, password) and stored_hash is not None
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def needs_rehash(stored_hash: str) -> bool:
    try:
        return _hasher.check_needs_rehash(stored_hash)
    except InvalidHashError:
        return True


def new_token() -> str:
    return secrets.token_hex(32)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def offline_uuid(name: str) -> str:
    """Same as Java UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8)), 32 hex chars."""
    digest = hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest()
    return uuid.UUID(bytes=digest, version=3).hex


def dashed(uuid_hex: str) -> str:
    return str(uuid.UUID(hex=uuid_hex))


def normalize_uuid(value: str) -> str | None:
    try:
        return uuid.UUID(value).hex
    except (ValueError, AttributeError, TypeError):
        return None


class RateLimited(Exception):
    def __init__(self, retry_after: int):
        super().__init__(f"rate limited, retry after {retry_after}s")
        self.retry_after = retry_after


def rate_limit(conn: sqlite3.Connection, bucket: str, limit: int, window: int, *, record: bool = True) -> None:
    """Sliding-window limiter stored in SQLite, so it survives restarts and works across workers."""
    now = time.time()
    conn.execute("DELETE FROM rate_events WHERE bucket = ? AND at < ?", (bucket, now - window))
    rows = conn.execute(
        "SELECT at FROM rate_events WHERE bucket = ? ORDER BY at ASC", (bucket,)
    ).fetchall()
    if len(rows) >= limit:
        retry = int(rows[0]["at"] + window - now) + 1
        raise RateLimited(max(retry, 1))
    if record:
        conn.execute("INSERT INTO rate_events(bucket, at) VALUES (?, ?)", (bucket, now))


def record_event(conn: sqlite3.Connection, bucket: str) -> None:
    conn.execute("INSERT INTO rate_events(bucket, at) VALUES (?, ?)", (bucket, time.time()))


def clear_bucket(conn: sqlite3.Connection, bucket: str) -> None:
    conn.execute("DELETE FROM rate_events WHERE bucket = ?", (bucket,))


def prune_rate_events(conn: sqlite3.Connection, older_than: int = 24 * 3600) -> None:
    conn.execute("DELETE FROM rate_events WHERE at < ?", (time.time() - older_than,))
