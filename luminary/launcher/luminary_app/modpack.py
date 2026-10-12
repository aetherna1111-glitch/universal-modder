"""Server-authoritative modpack sync (replaces the 1.x updater internals).

Same manifest format and config_updater.json as before (make_manifest.py is unchanged), but:
- every manifest path is confined to .minecraft/<sync folder>/ (no absolute paths, drive letters or '..');
- downloads go to a staging folder, are checked against size and SHA-256, and resume after interruption;
- files are moved into place only after *all* downloads verified, so an aborted update never leaves a
  half-updated pack; removed files are backed up first (as before);
- a (size, mtime) hash cache avoids re-hashing the whole pack on every launch;
- all of it runs off the GUI thread and reports progress.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Callable

log = logging.getLogger("luminary.modpack")

LOCAL_MANIFEST = ".luminary_manifest.json"
HASH_CACHE = ".luminary_hashcache.json"
STAGING = ".luminary_staging"
_SHA_RE = re.compile(r"^[0-9a-f]{64}$")
_WIN_RESERVED = {"con", "prn", "aux", "nul", *(f"com{i}" for i in range(1, 10)), *(f"lpt{i}" for i in range(1, 10))}
MAX_FILE_SIZE = 2 * 1024 ** 3


class PackError(Exception):
    """User-facing modpack problem."""


class Cancelled(Exception):
    pass


@dataclass(frozen=True)
class PackConfig:
    server_url: str
    manifest_name: str = "manifest.json"
    timeout: float = 30
    sync_folders: tuple[str, ...] = ()
    backup_before_delete: bool = True
    backup_folder: str = ".backup"
    block_launch_on_error: bool = False
    auto_update: bool = False

    @property
    def manifest_url(self) -> str:
        return self.server_url.rstrip("/") + "/" + urllib.parse.quote(self.manifest_name)


@dataclass(frozen=True)
class FileEntry:
    path: str
    sha256: str
    size: int


@dataclass
class Manifest:
    version: str
    files: list[FileEntry]
    raw: dict


@dataclass
class Plan:
    manifest: Manifest
    to_download: list[FileEntry] = field(default_factory=list)
    to_delete: list[str] = field(default_factory=list)
    up_to_date: int = 0

    @property
    def download_bytes(self) -> int:
        return sum(f.size for f in self.to_download)

    @property
    def empty(self) -> bool:
        return not self.to_download and not self.to_delete


def load_config(path: Path) -> PackConfig | None:
    if not path.exists():
        return None
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    url = str(raw.get("server_url", ""))
    u = urllib.parse.urlparse(url)
    if u.scheme != "https" and not (u.scheme == "http" and u.hostname in {"localhost", "127.0.0.1"}):
        raise PackError("config_updater.json: server_url должен быть https-адресом")
    folders = []
    for f in raw.get("sync_folders", []):
        f = str(f).strip("/")
        if not f or "/" in f or "\\" in f or f in {".", ".."} or f.startswith("."):
            raise PackError(f"config_updater.json: недопустимая папка синхронизации {f!r}")
        folders.append(f)
    backup = str(raw.get("backup_folder", ".backup"))
    if "/" in backup or "\\" in backup or backup in {"", ".", ".."}:
        raise PackError("config_updater.json: недопустимая backup_folder")
    return PackConfig(server_url=url, manifest_name=str(raw.get("manifest_name", "manifest.json")),
                      timeout=float(raw.get("timeout", 30)), sync_folders=tuple(folders),
                      backup_before_delete=bool(raw.get("backup_before_delete", True)), backup_folder=backup,
                      block_launch_on_error=bool(raw.get("block_launch_on_error", False)),
                      auto_update=bool(raw.get("auto_update", False)))


def safe_relpath(path: str, sync_folders: tuple[str, ...]) -> str:
    """Normalise a manifest path or raise PackError if it could escape .minecraft/<sync folder>/."""
    if not isinstance(path, str) or not path or len(path) > 400 or "\x00" in path:
        raise PackError("пустой или слишком длинный путь")
    if "\\" in path or ":" in path:
        raise PackError(f"недопустимые символы в пути: {path}")
    if PurePosixPath(path).is_absolute():
        raise PackError(f"абсолютный путь: {path}")
    parts = tuple(path.split("/"))  # raw split: "a//b" and "a/./b" are rejected, not normalised
    if any(part in {"", ".", ".."} for part in parts) or len(parts) < 2:
        raise PackError(f"недопустимый путь: {path}")
    for part in parts:
        stem = part.split(".")[0].lower()
        if stem in _WIN_RESERVED or part.endswith((" ", ".")):
            raise PackError(f"недопустимое имя файла: {path}")
    if parts[0] not in sync_folders:
        raise PackError(f"файл вне синхронизируемых папок: {path}")
    return "/".join(parts)


def parse_manifest(raw: dict, sync_folders: tuple[str, ...]) -> Manifest:
    if not isinstance(raw, dict) or not isinstance(raw.get("files"), list):
        raise PackError("manifest.json повреждён: нет списка files")
    files, seen = [], set()
    for item in raw["files"]:
        if not isinstance(item, dict):
            raise PackError("manifest.json повреждён")
        rel = safe_relpath(item.get("path"), sync_folders)
        sha = str(item.get("sha256", "")).lower()
        size = item.get("size")
        if not _SHA_RE.fullmatch(sha) or not isinstance(size, int) or not (0 <= size <= MAX_FILE_SIZE):
            raise PackError(f"manifest.json: неверный хеш или размер у {rel}")
        if rel.lower() in seen:
            raise PackError(f"manifest.json: файл указан дважды: {rel}")
        seen.add(rel.lower())
        files.append(FileEntry(rel, sha, size))
    return Manifest(str(raw.get("version", "?")), files, raw)


def _open(url: str, timeout: float, headers: dict | None = None):
    req = urllib.request.Request(url, headers={"User-Agent": "LuminaryLauncher", **(headers or {})})
    return urllib.request.urlopen(req, timeout=timeout)


def fetch_manifest(cfg: PackConfig) -> Manifest:
    try:
        with _open(cfg.manifest_url, cfg.timeout) as r:
            data = r.read(50 * 1024 * 1024)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            raise PackError("На сервере сборки ещё нет manifest.json") from None
        raise PackError(f"Сервер сборки ответил ошибкой {exc.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise PackError(f"Не удалось связаться с сервером сборки: {getattr(exc, 'reason', exc)}") from None
    try:
        raw = json.loads(data.decode("utf-8"))
    except ValueError:
        raise PackError("manifest.json повреждён (не JSON)") from None
    return parse_manifest(raw, cfg.sync_folders)


def sha256_file(path: Path, cancel: threading.Event | None = None, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            if cancel is not None and cancel.is_set():
                raise Cancelled()
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


class HashCache:
    """sha256 per relative path, valid while (size, mtime_ns) are unchanged."""

    def __init__(self, path: Path):
        self.path = path
        self.data: dict[str, list] = {}
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d, dict):
                self.data = d
        except (OSError, ValueError):
            pass
        self.dirty = False

    def get(self, rel: str, full: Path, cancel: threading.Event | None = None) -> str:
        st = full.stat()
        hit = self.data.get(rel)
        if hit and len(hit) == 3 and hit[0] == st.st_size and hit[1] == st.st_mtime_ns:
            return hit[2]
        digest = sha256_file(full, cancel)
        self.data[rel] = [st.st_size, st.st_mtime_ns, digest]
        self.dirty = True
        return digest

    def put(self, rel: str, full: Path, digest: str) -> None:
        st = full.stat()
        self.data[rel] = [st.st_size, st.st_mtime_ns, digest]
        self.dirty = True

    def forget(self, rel: str) -> None:
        if self.data.pop(rel, None) is not None:
            self.dirty = True

    def save(self) -> None:
        if not self.dirty:
            return
        tmp = self.path.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f)
        os.replace(tmp, self.path)
        self.dirty = False


ProgressFn = Callable[[int, int, str], None]


def make_plan(manifest: Manifest, mc_dir: Path, sync_folders: tuple[str, ...], cache: HashCache,
              progress: ProgressFn | None = None, cancel: threading.Event | None = None) -> Plan:
    plan = Plan(manifest)
    wanted = {f.path: f for f in manifest.files}
    total = len(manifest.files)
    for i, entry in enumerate(manifest.files):
        if cancel is not None and cancel.is_set():
            raise Cancelled()
        if progress:
            progress(i, total, f"Проверка: {entry.path}")
        full = mc_dir / entry.path
        if not full.is_file() or full.stat().st_size != entry.size:
            plan.to_download.append(entry)
            continue
        try:
            ok = cache.get(entry.path, full, cancel) == entry.sha256
        except OSError:
            ok = False
        if ok:
            plan.up_to_date += 1
        else:
            plan.to_download.append(entry)
    wanted_lower = {p.lower() for p in wanted}
    for folder in sync_folders:
        root = mc_dir / folder
        if not root.is_dir():
            continue
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                rel = Path(dirpath, name).relative_to(mc_dir).as_posix()
                if rel.lower() not in wanted_lower:
                    plan.to_delete.append(rel)
    plan.to_delete.sort()
    return plan


def _download(url: str, part: Path, entry: FileEntry, timeout: float, cancel: threading.Event | None,
              on_bytes: Callable[[int], None]) -> None:
    part.parent.mkdir(parents=True, exist_ok=True)
    have = part.stat().st_size if part.exists() else 0
    if have > entry.size:
        part.unlink()
        have = 0
    headers = {"Range": f"bytes={have}-"} if 0 < have < entry.size else {}
    if have == entry.size:
        if not part.exists():  # zero-byte file: nothing to download, but the staged file must exist
            part.touch()
        return
    with _open(url, timeout, headers) as r:
        mode = "ab" if headers and getattr(r, "status", 200) == 206 else "wb"
        if mode == "wb":
            have = 0
        on_bytes(have)
        with open(part, mode) as f:
            while True:
                if cancel is not None and cancel.is_set():
                    raise Cancelled()
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                have += len(chunk)
                if have > entry.size:
                    raise PackError(f"{entry.path}: файл больше, чем указано в манифесте")
                f.write(chunk)
                on_bytes(have)


def apply_plan(plan: Plan, cfg: PackConfig, mc_dir: Path, cache: HashCache,
               progress: ProgressFn | None = None, cancel: threading.Event | None = None,
               retries: int = 3) -> tuple[int, int]:
    """Download, verify, then swap in. Returns (downloaded, deleted)."""
    staging = mc_dir / STAGING
    total_bytes = max(plan.download_bytes, 1)
    done_bytes = 0
    base = cfg.server_url.rstrip("/") + "/"

    # 1) download and verify everything into staging (resumable; the live pack is untouched)
    for entry in plan.to_download:
        part = staging / (entry.path + ".part")
        url = base + urllib.parse.quote(entry.path, safe="/")
        for attempt in range(1, retries + 1):
            try:
                start = done_bytes

                def on_bytes(n, start=start, entry=entry):
                    if progress:
                        progress(start + n, total_bytes, f"Скачивание: {entry.path}")

                _download(url, part, entry, cfg.timeout, cancel, on_bytes)
                if part.stat().st_size != entry.size or sha256_file(part, cancel) != entry.sha256:
                    part.unlink(missing_ok=True)
                    raise PackError(f"{entry.path}: контрольная сумма не совпала")
                break
            except Cancelled:
                raise
            except (PackError, urllib.error.URLError, TimeoutError, OSError) as exc:
                log.warning("download %s attempt %d failed: %s", entry.path, attempt, exc)
                if attempt == retries:
                    if isinstance(exc, urllib.error.HTTPError) and exc.code == 404:
                        raise PackError(f"На сервере сборки нет файла {entry.path}") from None
                    raise PackError(f"Не удалось скачать {entry.path}: {getattr(exc, 'reason', exc)}") from None
                time.sleep(min(2 ** attempt, 8))
        done_bytes += entry.size

    if cancel is not None and cancel.is_set():
        raise Cancelled()

    # 2) swap verified files into place (os.replace is atomic per file)
    for entry in plan.to_download:
        dest = mc_dir / entry.path
        dest.parent.mkdir(parents=True, exist_ok=True)
        os.replace(staging / (entry.path + ".part"), dest)
        cache.put(entry.path, dest, entry.sha256)  # verified above; no need to hash it again next launch

    # 3) remove files the server no longer ships (backed up first, as 1.x did)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    deleted = 0
    for rel in plan.to_delete:
        src = mc_dir / rel
        if not src.exists():
            continue
        if progress:
            progress(total_bytes, total_bytes, f"Удаление: {rel}")
        if cfg.backup_before_delete:
            dst = mc_dir / cfg.backup_folder / stamp / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
        else:
            src.unlink()
        cache.forget(rel)
        deleted += 1

    with open(mc_dir / LOCAL_MANIFEST, "w", encoding="utf-8") as f:
        json.dump(plan.manifest.raw, f, indent=2, ensure_ascii=False)
    shutil.rmtree(staging, ignore_errors=True)
    cache.save()
    return len(plan.to_download), deleted


def local_version(mc_dir: Path) -> str | None:
    try:
        with open(mc_dir / LOCAL_MANIFEST, encoding="utf-8") as f:
            return str(json.load(f).get("version") or "") or None
    except (OSError, ValueError, AttributeError):
        return None


def local_manifest(mc_dir: Path, sync_folders: tuple[str, ...]) -> Manifest | None:
    try:
        with open(mc_dir / LOCAL_MANIFEST, encoding="utf-8") as f:
            return parse_manifest(json.load(f), sync_folders)
    except (OSError, ValueError, PackError):
        return None
