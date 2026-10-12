"""Launcher self-update: signed release manifests, staged downloads, apply-on-start with rollback.

Standard library only: launcher.py applies a pending update *before* Qt and the rest of the app load.

Publishing (owner, see publish_launcher.py) uploads to <update url>:
    launcher.json                  {"format": 1, "payload": base64(JSON), "signature": hex(Ed25519)}
    <version>/<path>               the release's files
The payload JSON is {"product", "version", "published", "notes", "files": [{"path", "sha256", "size"}]}.

Players' launchers:
  1. check(): download launcher.json, verify the Ed25519 signature with the public key from
     profiles.json, refuse versions that are not newer (no downgrade/replay) or that failed before;
  2. stage(): download changed files into launcher_data/update/staging/<version>/, verify size + SHA-256,
     then write pending.json;
  3. apply_pending() (next start, from launcher.py): back up the files it replaces, copy the staged files
     in, delete program files the release no longer has; any failure restores the backup;
  4. if the new version fails to start, launcher.py calls rollback(); once the window is up,
     confirm_ok() discards the backup.
Only the launcher's own files are managed (luminary_app/, assets/, sounds/ and a fixed list of root
files). python/, jre/, the game installer and player data are never touched.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

from . import ed25519

FORMAT = 1
PRODUCT = "luminary-launcher"
MANIFEST = "launcher.json"
MANAGED_DIRS = ("luminary_app", "assets", "sounds")
MANAGED_FILES = ("launcher.py", "profiles.json", "config_updater.json", "luminary.ico", "run.bat",
                 "run-debug.bat", "README.md", "Luminary.exe")
MAX_FILE = 64 * 1024 * 1024
MAX_ATTEMPTS = 3


class UpdateError(Exception):
    """User-facing update problem."""


@dataclass(frozen=True)
class FileEntry:
    path: str
    sha256: str
    size: int


@dataclass
class Release:
    version: str
    notes: str
    files: list[FileEntry]
    published: str = ""


@dataclass
class Staged:
    version: str
    notes: str
    changed: int
    deleted: int = 0


@dataclass
class UpdateConfig:
    url: str
    public_key: bytes

    @property
    def manifest_url(self) -> str:
        return self.url.rstrip("/") + "/" + MANIFEST

    def file_url(self, version: str, path: str) -> str:
        return f"{self.url.rstrip('/')}/{urllib.parse.quote(version)}/{urllib.parse.quote(path, safe='/')}"


# ---------------------------------------------------------------------------------------------- helpers

def parse_version(v: str) -> tuple[int, ...]:
    parts = str(v).strip().split(".")
    if not 1 <= len(parts) <= 4 or not all(p.isdigit() and len(p) <= 6 for p in parts):
        raise UpdateError(f"неверный номер версии: {v!r}")
    return tuple(int(p) for p in parts) + (0,) * (4 - len(parts))


def is_newer(candidate: str, current: str) -> bool:
    return parse_version(candidate) > parse_version(current)


def managed_path(rel: str) -> str:
    """Normalise a release path; refuse anything outside the launcher's own files."""
    if not isinstance(rel, str) or not rel or len(rel) > 300 or "\\" in rel or ":" in rel or "\x00" in rel:
        raise UpdateError(f"недопустимый путь в обновлении: {rel!r}")
    if PurePosixPath(rel).is_absolute():
        raise UpdateError(f"недопустимый путь в обновлении: {rel!r}")
    parts = rel.split("/")
    if any(p in ("", ".", "..") or p.endswith((" ", ".")) for p in parts):
        raise UpdateError(f"недопустимый путь в обновлении: {rel!r}")
    if len(parts) == 1:
        if rel not in MANAGED_FILES:
            raise UpdateError(f"обновление не может менять {rel}")
    elif parts[0] not in MANAGED_DIRS or "__pycache__" in parts:
        raise UpdateError(f"обновление не может менять {rel}")
    return rel


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def state_dir(base: Path) -> Path:
    return base / "launcher_data" / "update"


def _read_json(path: Path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def bad_versions(base: Path) -> set[str]:
    data = _read_json(state_dir(base) / "bad.json")
    return set(data) if isinstance(data, list) else set()


def _mark_bad(base: Path, version: str) -> None:
    bad = bad_versions(base)
    bad.add(version)
    _write_json(state_dir(base) / "bad.json", sorted(bad))


# ---------------------------------------------------------------------------------------------- manifests

def parse_release(raw: bytes, public_key: bytes) -> Release:
    try:
        envelope = json.loads(raw.decode("utf-8"))
        payload = base64.b64decode(envelope["payload"], validate=True)
        signature = bytes.fromhex(envelope["signature"])
    except (ValueError, KeyError, TypeError, binascii.Error, UnicodeDecodeError, AttributeError):
        raise UpdateError("описание обновления повреждено") from None
    if envelope.get("format") != FORMAT:
        raise UpdateError("неизвестный формат обновления — скачай новый установщик")
    if not ed25519.verify(public_key, payload, signature):
        raise UpdateError("подпись обновления не прошла проверку — обновление отклонено")
    try:
        data = json.loads(payload.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise UpdateError("описание обновления повреждено") from None
    if not isinstance(data, dict) or data.get("product") != PRODUCT or not isinstance(data.get("files"), list):
        raise UpdateError("описание обновления повреждено")
    version = str(data.get("version", ""))
    parse_version(version)
    files, seen = [], set()
    for item in data["files"]:
        if not isinstance(item, dict):
            raise UpdateError("описание обновления повреждено")
        rel = managed_path(item.get("path"))
        sha = str(item.get("sha256", "")).lower()
        size = item.get("size")
        if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha) or not isinstance(size, int) \
                or not 0 <= size <= MAX_FILE:
            raise UpdateError(f"неверный хеш или размер у {rel}")
        if rel.lower() in seen:
            raise UpdateError(f"файл указан дважды: {rel}")
        seen.add(rel.lower())
        files.append(FileEntry(rel, sha, size))
    if "launcher.py" not in seen or not any(f.path.startswith("luminary_app/") for f in files):
        raise UpdateError("в обновлении нет программы лаунчера")
    return Release(version, str(data.get("notes", ""))[:2000], files, str(data.get("published", "")))


def make_manifest(version: str, files: list[FileEntry], secret: bytes, notes: str = "",
                  published: str = "") -> bytes:
    """Publisher side: the signed launcher.json."""
    parse_version(version)
    payload = json.dumps({"product": PRODUCT, "version": version, "published": published, "notes": notes,
                          "files": [{"path": f.path, "sha256": f.sha256, "size": f.size} for f in files]},
                         ensure_ascii=False, indent=1).encode("utf-8")
    envelope = {"format": FORMAT, "payload": base64.b64encode(payload).decode("ascii"),
                "signature": ed25519.sign(secret, payload).hex()}
    return json.dumps(envelope, indent=1).encode("utf-8")


def collect_files(source: Path) -> list[FileEntry]:
    """Publisher side: the managed files of a launcher folder."""
    out = []
    for name in MANAGED_FILES:
        p = source / name
        if p.is_file():
            out.append(FileEntry(name, sha256_file(p), p.stat().st_size))
    for d in MANAGED_DIRS:
        root = source / d
        if not root.is_dir():
            continue
        for p in sorted(root.rglob("*")):
            if p.is_file() and "__pycache__" not in p.parts and p.suffix not in (".pyc", ".pyo"):
                rel = managed_path(p.relative_to(source).as_posix())
                out.append(FileEntry(rel, sha256_file(p), p.stat().st_size))
    return out


# ---------------------------------------------------------------------------------------------- network

def _get(url: str, timeout: float, limit: int) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "LuminaryLauncher-Updater", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read(limit + 1)
    if len(data) > limit:
        raise UpdateError("ответ сервера обновлений слишком большой")
    return data


def check(cfg: UpdateConfig, base: Path, current: str, timeout: float = 20) -> Release | None:
    """The newer, signed release, or None when there is nothing to install."""
    try:
        raw = _get(cfg.manifest_url, timeout, 4 * 1024 * 1024)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None  # nothing published yet
        raise UpdateError(f"сервер обновлений ответил ошибкой {exc.code}") from None
    except (urllib.error.URLError, OSError) as exc:
        raise UpdateError(f"нет связи с сервером обновлений: {getattr(exc, 'reason', exc)}") from None
    release = parse_release(raw, cfg.public_key)
    if not is_newer(release.version, current) or release.version in bad_versions(base):
        return None
    return release


def stage(cfg: UpdateConfig, base: Path, release: Release, progress=None, cancel=None,
          timeout: float = 60) -> Staged:
    """Download what differs, verify it, and mark the update pending for the next start."""
    st = state_dir(base)
    staging = st / "staging" / release.version
    pending = _read_json(st / "pending.json")
    if isinstance(pending, dict) and pending.get("version") == release.version:
        return Staged(release.version, release.notes, len(pending.get("files", [])), len(pending.get("deletes", [])))
    changed = [f for f in release.files
               if not ((base / f.path).is_file() and (base / f.path).stat().st_size == f.size
                       and sha256_file(base / f.path) == f.sha256)]
    total = max(sum(f.size for f in changed), 1)
    done = 0
    for f in changed:
        if cancel is not None and cancel.is_set():
            raise UpdateError("обновление отменено")
        dest = staging / f.path
        if not (dest.is_file() and dest.stat().st_size == f.size and sha256_file(dest) == f.sha256):
            dest.parent.mkdir(parents=True, exist_ok=True)
            try:
                data = _get(cfg.file_url(release.version, f.path), timeout, MAX_FILE)
            except urllib.error.HTTPError as exc:
                raise UpdateError(f"на сервере обновлений нет файла {f.path} ({exc.code})") from None
            except (urllib.error.URLError, OSError) as exc:
                raise UpdateError(f"не удалось скачать {f.path}: {getattr(exc, 'reason', exc)}") from None
            if len(data) != f.size or hashlib.sha256(data).hexdigest() != f.sha256:
                raise UpdateError(f"{f.path}: контрольная сумма не совпала — обновление отклонено")
            tmp = dest.with_name(dest.name + ".part")
            with open(tmp, "wb") as out:
                out.write(data)
            os.replace(tmp, dest)
        done += f.size
        if progress:
            progress(done / total, f.path)
    wanted = {f.path.lower() for f in release.files}
    deletes = []
    app_dir = base / "luminary_app"
    if app_dir.is_dir():
        for p in sorted(app_dir.rglob("*")):
            rel = p.relative_to(base).as_posix()
            if p.is_file() and "__pycache__" not in p.parts and p.suffix not in (".pyc", ".pyo") \
                    and rel.lower() not in wanted:
                deletes.append(rel)
    _write_json(st / "pending.json", {"version": release.version, "notes": release.notes, "attempts": 0,
                                      "files": [{"path": f.path, "sha256": f.sha256} for f in changed],
                                      "deletes": deletes, "staged_at": int(time.time())})
    return Staged(release.version, release.notes, len(changed), len(deletes))


def pending_version(base: Path) -> str | None:
    p = _read_json(state_dir(base) / "pending.json")
    return p.get("version") if isinstance(p, dict) else None


# ---------------------------------------------------------------------------------------------- apply / rollback

def apply_pending(base: Path, current: str) -> str | None:
    """Install a staged update. Returns the new version, or None if there was nothing (valid) to apply."""
    st = state_dir(base)
    pending = _read_json(st / "pending.json")
    if not isinstance(pending, dict):
        return None
    version = str(pending.get("version", ""))
    staging = st / "staging" / version
    try:
        if not is_newer(version, current) or version in bad_versions(base):
            raise UpdateError("устаревшее обновление")
        files = [(managed_path(f["path"]), str(f["sha256"])) for f in pending.get("files", [])]
        deletes = [managed_path(d) for d in pending.get("deletes", [])]
        for rel, sha in files:  # re-verify: the staging folder lives on the player's disk
            if sha256_file(staging / rel) != sha:
                raise UpdateError(f"{rel}: подготовленный файл повреждён")
    except (UpdateError, OSError, KeyError, TypeError) as exc:
        _discard(st, version)
        return _log_none(base, f"pending update {version} dropped: {exc}")

    backup = st / "backup" / version
    shutil.rmtree(backup, ignore_errors=True)
    added, replaced = [], []
    try:
        for rel in [r for r, _ in files] + deletes:
            src = base / rel
            if src.is_file():
                dst = backup / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)
                replaced.append(rel)
        for rel, _sha in files:
            dest = base / rel
            if not dest.exists():
                added.append(rel)
            dest.parent.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_name(dest.name + ".luminary-new")
            shutil.copy2(staging / rel, tmp)
            os.replace(tmp, dest)
        for rel in deletes:
            (base / rel).unlink(missing_ok=True)
        _purge_bytecode(base)
    except OSError as exc:  # e.g. a file locked by another program: put everything back, retry next start
        _restore(base, backup, replaced, added)
        pending["attempts"] = int(pending.get("attempts", 0)) + 1
        if pending["attempts"] >= MAX_ATTEMPTS:
            _discard(st, version)
            _mark_bad(base, version)
        else:
            _write_json(st / "pending.json", pending)
        return _log_none(base, f"applying update {version} failed, restored: {exc}")

    _write_json(st / "applied.json", {"version": version, "previous": current, "replaced": replaced,
                                      "added": added, "applied_at": int(time.time())})
    (st / "pending.json").unlink(missing_ok=True)
    shutil.rmtree(st / "staging", ignore_errors=True)
    _log(base, f"applied launcher update {current} -> {version}")
    return version


def has_unconfirmed(base: Path) -> bool:
    return (state_dir(base) / "applied.json").is_file()


def rollback(base: Path) -> str | None:
    """Undo the last applied update (called when the new version cannot start). Returns the bad version."""
    st = state_dir(base)
    applied = _read_json(st / "applied.json")
    if not isinstance(applied, dict):
        return None
    version = str(applied.get("version", ""))
    try:
        replaced = [managed_path(r) for r in applied.get("replaced", [])]
        added = [managed_path(a) for a in applied.get("added", [])]
    except UpdateError:
        replaced, added = [], []
    _restore(base, st / "backup" / version, replaced, added)
    _purge_bytecode(base)
    _mark_bad(base, version)
    (st / "applied.json").unlink(missing_ok=True)
    shutil.rmtree(st / "backup" / version, ignore_errors=True)
    _log(base, f"rolled back launcher update {version}")
    return version


def confirm_ok(base: Path) -> None:
    """The updated launcher is up: forget the backup."""
    st = state_dir(base)
    applied = _read_json(st / "applied.json")
    if isinstance(applied, dict):
        shutil.rmtree(st / "backup", ignore_errors=True)
        (st / "applied.json").unlink(missing_ok=True)


def _restore(base: Path, backup: Path, replaced: list[str], added: list[str]) -> None:
    for rel in added:
        try:
            (base / rel).unlink(missing_ok=True)
        except OSError:
            pass
    for rel in replaced:
        src = backup / rel
        if src.is_file():
            dest = base / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)


def _discard(st: Path, version: str) -> None:
    (st / "pending.json").unlink(missing_ok=True)
    shutil.rmtree(st / "staging", ignore_errors=True)


def _purge_bytecode(base: Path) -> None:
    for d in (base / "luminary_app").rglob("__pycache__"):
        shutil.rmtree(d, ignore_errors=True)


def _log(base: Path, text: str) -> None:
    try:
        d = base / "logs"
        d.mkdir(parents=True, exist_ok=True)
        with open(d / "update.log", "a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
    except OSError:
        pass


def _log_none(base: Path, text: str):
    _log(base, text)
    return None


def restart_command(base: Path) -> list[str]:
    """Command that starts the launcher again (the same windowless interpreter)."""
    return [sys.executable, str(base / "launcher.py"), "--after-update"]
