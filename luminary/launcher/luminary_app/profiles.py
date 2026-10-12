"""Launch profiles (profiles.json): Minecraft version, loader, server, modpack, Java.

A new modpack is a new entry in "profiles"; nothing in the launcher core is specific to Luminary's pack.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

from .paths import IS_WINDOWS

LOADERS = {"vanilla", "fabric", "quilt", "forge", "neoforge"}
_VERSION_RE = re.compile(r"^[0-9A-Za-z._+-]{1,64}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class ProfileError(ValueError):
    pass


@dataclass(frozen=True)
class Server:
    name: str
    host: str
    port: int


@dataclass(frozen=True)
class Profile:
    id: str
    name: str
    minecraft: str
    loader: str
    loader_version: str | None
    server: Server | None
    modpack_config: str | None
    java_windows: str
    java_other: str
    java_min_version: int = 17

    def portablemc_version(self) -> str:
        """Version specifier understood by PortableMC 5 (e.g. fabric:1.20.1 or fabric:1.20.1:0.16.10)."""
        if self.loader == "vanilla":
            return f"mojang:{self.minecraft}"
        spec = f"{self.loader}:{self.minecraft}"
        if self.loader_version:
            spec += f":{self.loader_version}"
        return spec

    def java_relpath(self) -> str:
        return self.java_windows if IS_WINDOWS else self.java_other

    def loader_label(self) -> str:
        names = {"vanilla": "Vanilla", "fabric": "Fabric", "quilt": "Quilt", "forge": "Forge", "neoforge": "NeoForge"}
        label = names[self.loader]
        return f"{label} {self.loader_version}" if self.loader_version else label


@dataclass(frozen=True)
class AuthlibInjector:
    version: str
    url: str
    sha256: str

    @property
    def filename(self) -> str:
        return f"authlib-injector-{self.version}.jar"


@dataclass(frozen=True)
class Catalog:
    profiles: tuple[Profile, ...]
    default_profile: str
    api_url: str
    accounts_required: bool
    authlib: AuthlibInjector | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)
    update_url: str = ""          # launcher self-update host (folder with launcher.json)
    update_key: bytes = b""       # Ed25519 public key that signs launcher releases

    def get(self, profile_id: str | None) -> Profile:
        for p in self.profiles:
            if p.id == profile_id:
                return p
        for p in self.profiles:
            if p.id == self.default_profile:
                return p
        return self.profiles[0]

    @property
    def accounts_enabled(self) -> bool:
        return bool(self.api_url)


def validate_api_url(url: str) -> str:
    url = (url or "").strip().rstrip("/")
    if not url:
        return ""
    u = urlparse(url)
    if u.scheme == "https" and u.hostname:
        return url
    if u.scheme == "http" and u.hostname in {"localhost", "127.0.0.1", "::1"}:
        return url
    raise ProfileError("Адрес сервера аккаунтов должен начинаться с https://")


def _safe_relpath(value: str, what: str) -> str:
    p = Path(value)
    if not value or p.is_absolute() or ".." in p.parts or ":" in value or value.startswith(("/", "\\")):
        raise ProfileError(f"{what}: путь должен быть относительным и без '..'")
    return value


def _profile(raw: dict) -> Profile:
    try:
        pid = str(raw["id"])
        mc = str(raw["minecraft"])
        loader = raw.get("loader") or {"type": "vanilla"}
        ltype = str(loader.get("type", "vanilla")).lower()
        lver = loader.get("version")
    except (KeyError, TypeError, AttributeError) as exc:
        raise ProfileError(f"неполный профиль: {exc}") from exc
    if not re.fullmatch(r"[a-z0-9_-]{1,32}", pid):
        raise ProfileError(f"неверный id профиля: {pid!r}")
    if not _VERSION_RE.fullmatch(mc):
        raise ProfileError(f"{pid}: неверная версия Minecraft")
    if ltype not in LOADERS:
        raise ProfileError(f"{pid}: неизвестный загрузчик {ltype!r}")
    if lver is not None and not _VERSION_RE.fullmatch(str(lver)):
        raise ProfileError(f"{pid}: неверная версия загрузчика")
    server = None
    if raw.get("server"):
        s = raw["server"]
        port = int(s.get("port", 25565))
        if not (0 < port < 65536) or not str(s.get("host", "")).strip():
            raise ProfileError(f"{pid}: неверный адрес сервера")
        server = Server(str(s.get("name") or raw.get("name") or pid), str(s["host"]).strip(), port)
    java = raw.get("java") or {}
    modpack = (raw.get("modpack") or {}).get("config")
    return Profile(
        id=pid, name=str(raw.get("name") or pid), minecraft=mc, loader=ltype,
        loader_version=str(lver) if lver is not None else None, server=server,
        modpack_config=_safe_relpath(str(modpack), "modpack.config") if modpack else None,
        java_windows=_safe_relpath(str(java.get("windows", "jre/bin/javaw.exe")), "java.windows"),
        java_other=_safe_relpath(str(java.get("other", "jre/bin/java")), "java.other"),
        java_min_version=int(java.get("min_version", 17)),
    )


# Used when profiles.json is missing: exactly what launcher 1.x hard-coded.
FALLBACK = {
    "schema": 1,
    "default_profile": "luminary",
    "accounts": {"api_url": "", "required": False},
    "profiles": [{
        "id": "luminary", "name": "Luminary", "minecraft": "1.20.1",
        "loader": {"type": "fabric", "version": "0.19.5"},
        "server": {"name": "Luminary", "host": "135.106.221.145", "port": 27195},
        "modpack": {"config": "config_updater.json"},
        "java": {"windows": "jre/bin/javaw.exe", "other": "jre/bin/java", "min_version": 17},
    }],
}


def parse_catalog(data: dict) -> Catalog:
    if not isinstance(data, dict) or not isinstance(data.get("profiles"), list) or not data["profiles"]:
        raise ProfileError("profiles.json: нет списка профилей")
    profiles, warnings, seen = [], [], set()
    for raw in data["profiles"]:
        try:
            p = _profile(raw)
        except (ProfileError, ValueError, TypeError) as exc:
            warnings.append(str(exc))
            continue
        if p.id in seen:
            warnings.append(f"повтор профиля {p.id}")
            continue
        seen.add(p.id)
        profiles.append(p)
    if not profiles:
        raise ProfileError("profiles.json: ни одного корректного профиля")
    acc = data.get("accounts") or {}
    api_url = validate_api_url(str(acc.get("api_url") or ""))
    authlib = None
    ai = data.get("authlib_injector")
    if ai:
        url = str(ai.get("url", ""))
        sha = str(ai.get("sha256", "")).lower()
        if urlparse(url).scheme != "https" or not _SHA256_RE.fullmatch(sha):
            raise ProfileError("authlib_injector: нужен https-адрес и sha256")
        authlib = AuthlibInjector(str(ai.get("version", "unknown")), url, sha)
    upd = data.get("launcher_update") or {}
    update_url, update_key = "", b""
    if upd.get("url"):
        update_url = validate_api_url(str(upd["url"]))
        try:
            update_key = bytes.fromhex(str(upd.get("public_key", "")))
        except ValueError:
            update_key = b""
        if len(update_key) != 32:
            warnings.append("launcher_update: нужен public_key (64 hex-символа); автообновление выключено")
            update_url, update_key = "", b""
    return Catalog(tuple(profiles), str(data.get("default_profile") or profiles[0].id), api_url,
                   bool(acc.get("required", False)) and bool(api_url), authlib, tuple(warnings),
                   update_url, update_key)


def load_catalog(path: Path) -> Catalog:
    if not path.exists():
        return parse_catalog(FALLBACK)
    with open(path, encoding="utf-8") as f:
        return parse_catalog(json.load(f))
