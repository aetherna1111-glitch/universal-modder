"""Read installed Fabric mods (fabric.mod.json) and check them against the profile.

Read-only: the Luminary pack is server-authoritative (config_updater.json sync), so mods are installed and
removed by updating the server manifest, not from the launcher.
"""
from __future__ import annotations

import io
import json
import logging
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("luminary.mods")

MAX_META_BYTES = 1_000_000
MAX_NESTED_BYTES = 32 * 1024 * 1024
BUILTIN_IDS = {"minecraft", "java", "fabricloader", "fabric-loader"}


@dataclass
class ModInfo:
    file: str
    size: int
    id: str | None = None
    name: str | None = None
    version: str | None = None
    description: str = ""
    authors: list[str] = field(default_factory=list)
    depends: dict[str, object] = field(default_factory=dict)
    provides: set[str] = field(default_factory=set)
    environment: str = "*"
    error: str | None = None
    issues: list[str] = field(default_factory=list)
    sync_state: str = "unknown"  # in_pack | modified | extra | unknown

    @property
    def title(self) -> str:
        return self.name or self.id or self.file


def _read_meta(zf: zipfile.ZipFile) -> dict | None:
    try:
        info = zf.getinfo("fabric.mod.json")
    except KeyError:
        return None
    if info.file_size > MAX_META_BYTES:
        raise ValueError("fabric.mod.json too large")
    raw = zf.read(info).decode("utf-8-sig", errors="replace")
    # Some mods ship fabric.mod.json with raw newlines inside strings; json.loads(strict=False) accepts them.
    return json.loads(raw, strict=False)


def _authors(meta: dict) -> list[str]:
    out = []
    for a in meta.get("authors", []) or []:
        if isinstance(a, str):
            out.append(a)
        elif isinstance(a, dict) and isinstance(a.get("name"), str):
            out.append(a["name"])
    return out


def _nested_ids(zf: zipfile.ZipFile, meta: dict, budget: list[int]) -> set[str]:
    ids: set[str] = set()
    for j in meta.get("jars", []) or []:
        path = j.get("file") if isinstance(j, dict) else None
        if not isinstance(path, str):
            continue
        try:
            info = zf.getinfo(path)
        except KeyError:
            continue
        if info.file_size > budget[0]:
            continue
        budget[0] -= info.file_size
        try:
            with zipfile.ZipFile(io.BytesIO(zf.read(info))) as inner:
                m = _read_meta(inner)
                if m and isinstance(m.get("id"), str):
                    ids.add(m["id"])
                    ids.update(p for p in m.get("provides", []) or [] if isinstance(p, str))
                    ids |= _nested_ids(inner, m, budget)
        except (zipfile.BadZipFile, ValueError, OSError):
            continue
    return ids


def read_mod(path: Path) -> ModInfo:
    mi = ModInfo(file=path.name, size=path.stat().st_size)
    try:
        with zipfile.ZipFile(path) as zf:
            meta = _read_meta(zf)
            if meta is None:
                mi.error = "Не Fabric-мод (нет fabric.mod.json)"
                return mi
            mi.id = str(meta.get("id") or "") or None
            mi.name = str(meta.get("name") or "") or None
            mi.version = str(meta.get("version") or "") or None
            desc = meta.get("description")
            mi.description = desc if isinstance(desc, str) else ""
            mi.authors = _authors(meta)
            deps = meta.get("depends") or {}
            mi.depends = deps if isinstance(deps, dict) else {}
            env = meta.get("environment", "*")
            mi.environment = env if isinstance(env, str) else "*"
            mi.provides = {p for p in meta.get("provides", []) or [] if isinstance(p, str)}
            mi.provides |= _nested_ids(zf, meta, [MAX_NESTED_BYTES])
    except (zipfile.BadZipFile, OSError) as exc:
        mi.error = f"Повреждённый архив: {exc}"
    except ValueError as exc:
        mi.error = f"Не удалось прочитать fabric.mod.json: {exc}"
    return mi


# ------------------------------------------------------------------ version predicates

_NUM_RE = re.compile(r"^(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$")


def _parse_version(v: str):
    m = _NUM_RE.match(v.strip())
    if not m:
        return None
    nums = tuple(int(x) for x in m.group(1).split("."))
    return nums, m.group(2)


def _cmp(a, b) -> int:
    an, ap = a
    bn, bp = b
    n = max(len(an), len(bn))
    an, bn = an + (0,) * (n - len(an)), bn + (0,) * (n - len(bn))
    if an != bn:
        return -1 if an < bn else 1
    if ap == bp:
        return 0
    if ap is None:
        return 1
    if bp is None:
        return -1
    return -1 if ap < bp else 1


def _match_one(pred: str, version: str) -> bool | None:
    pred = pred.strip()
    if pred in ("", "*"):
        return True
    v = _parse_version(version)
    if v is None:
        return None
    for op in (">=", "<=", ">", "<", "=", "~", "^"):
        if pred.startswith(op):
            target = pred[len(op):].strip()
            break
    else:
        op, target = "", pred
    if "x" in target.lower().split(".") or target.lower().endswith(".x") or target.endswith(".*"):
        prefix = [p for p in re.split(r"\.", target) if p.lower() not in ("x", "*")]
        try:
            pnums = tuple(int(p) for p in prefix)
        except ValueError:
            return None
        return v[0][:len(pnums)] == pnums
    t = _parse_version(target)
    if t is None:
        return None
    c = _cmp(v, t)
    if op in ("", "="):
        return c == 0
    if op == ">=":
        return c >= 0
    if op == "<=":
        return c <= 0
    if op == ">":
        return c > 0
    if op == "<":
        return c < 0
    tn = t[0]
    if op == "~":  # same major.minor, >= target
        upper = (tn[0], (tn[1] if len(tn) > 1 else 0) + 1)
    else:  # ^ same major
        upper = (tn[0] + 1,)
    return c >= 0 and _cmp(v, (upper, None)) < 0


def matches(predicate, version: str) -> bool | None:
    """Fabric dependency predicate (string or list of strings = OR). None if it cannot be evaluated."""
    preds = predicate if isinstance(predicate, list) else [predicate]
    results = []
    for p in preds:
        if not isinstance(p, str):
            return None
        parts = p.split()
        sub = [_match_one(x, version) for x in parts] if parts else [True]
        if None in sub:
            results.append(None)
        else:
            results.append(all(sub))
    if any(r is True for r in results):
        return True
    if any(r is None for r in results):
        return None
    return False


def check(mods: list[ModInfo], minecraft: str) -> None:
    present = set()
    for m in mods:
        if m.id:
            present.add(m.id)
            present |= m.provides
    for m in mods:
        if m.error:
            continue
        for dep, pred in m.depends.items():
            if dep == "minecraft":
                ok = matches(pred, minecraft)
                if ok is False:
                    m.issues.append(f"Требует Minecraft {pred}, а в профиле {minecraft}")
            elif dep in BUILTIN_IDS:
                continue
            elif dep not in present:
                m.issues.append(f"Нет зависимости: {dep}")


def scan(mods_dir: Path, minecraft: str, manifest_hashes: dict[str, str] | None = None,
         file_hash=None) -> list[ModInfo]:
    if not mods_dir.is_dir():
        return []
    mods = [read_mod(p) for p in sorted(mods_dir.glob("*.jar"), key=lambda p: p.name.lower())]
    check(mods, minecraft)
    if manifest_hashes is not None and file_hash is not None:
        for m in mods:
            rel = f"mods/{m.file}"
            expected = manifest_hashes.get(rel)
            if expected is None:
                m.sync_state = "extra"
            else:
                try:
                    m.sync_state = "in_pack" if file_hash(rel, mods_dir / m.file) == expected else "modified"
                except OSError:
                    m.sync_state = "unknown"
    return mods
