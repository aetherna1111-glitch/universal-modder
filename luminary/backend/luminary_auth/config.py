"""Runtime configuration, read from environment variables (see deploy/luminary-auth.env.example)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer, got {raw!r}") from exc


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass
class Settings:
    data_dir: Path
    public_url: str                      # e.g. https://auth.example.org (no trailing slash)
    server_name: str = "Luminary"
    registration_open: bool = True
    trust_proxy: bool = False            # honour X-Forwarded-For from a local reverse proxy
    session_ttl: int = 30 * 24 * 3600    # launcher sessions
    game_token_ttl: int = 48 * 3600      # tokens handed to the game (authlib-injector)
    join_ttl: int = 30                   # seconds between client join and server hasJoined
    presence_ttl: int = 150
    max_skin_bytes: int = 128 * 1024
    extra_skin_domains: list[str] = field(default_factory=list)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "luminary.sqlite3"

    @property
    def textures_dir(self) -> Path:
        return self.data_dir / "textures"

    @property
    def key_path(self) -> Path:
        return self.data_dir / "signing_key.pem"

    @property
    def public_host(self) -> str:
        return urlparse(self.public_url).hostname or "localhost"

    @property
    def skin_domains(self) -> list[str]:
        return [self.public_host, *self.extra_skin_domains]

    def validate(self) -> None:
        u = urlparse(self.public_url)
        if u.scheme not in {"http", "https"} or not u.hostname:
            raise ValueError("LUMINARY_PUBLIC_URL must be an absolute http(s) URL")
        if u.scheme == "http" and u.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("LUMINARY_PUBLIC_URL must use https outside local development")
        if self.public_url.endswith("/"):
            raise ValueError("LUMINARY_PUBLIC_URL must not end with '/'")


def from_env() -> Settings:
    data_dir = Path(os.environ.get("LUMINARY_DATA_DIR", "./data")).resolve()
    extra = [d.strip() for d in os.environ.get("LUMINARY_EXTRA_SKIN_DOMAINS", "").split(",") if d.strip()]
    s = Settings(
        data_dir=data_dir,
        public_url=os.environ.get("LUMINARY_PUBLIC_URL", "http://localhost:8080").rstrip("/"),
        server_name=os.environ.get("LUMINARY_SERVER_NAME", "Luminary"),
        registration_open=_bool("LUMINARY_REGISTRATION_OPEN", True),
        trust_proxy=_bool("LUMINARY_TRUST_PROXY", False),
        session_ttl=_int("LUMINARY_SESSION_TTL", 30 * 24 * 3600),
        game_token_ttl=_int("LUMINARY_GAME_TOKEN_TTL", 48 * 3600),
        extra_skin_domains=extra,
    )
    s.validate()
    return s
