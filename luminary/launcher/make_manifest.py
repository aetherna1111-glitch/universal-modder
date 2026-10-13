# make_manifest.py - builds and verifies manifest.json for the modpack server (developer tool).
#
#   python\python.exe make_manifest.py                  build manifest.json from .minecraft\<sync_folders>
#   python\python.exe make_manifest.py --check          verify manifest.json against the local files
#   python\python.exe make_manifest.py --check-remote   download every published file and verify it
#
# Format: {"version", "updated", "folders": {"<folder>": "mirror"|"update"}, "files": [{"path", "sha256", "size"}]}.
# "folders" (since launcher 2.1.2) comes from config_updater.json sync_folders + folder_modes, so the published
# manifest, not the launcher build, decides which .minecraft folders players get (see luminary_app/modpack.py).
# Older launchers ignore "folders". Launchers before 2.1.2 reject a manifest with a folder they do not know, so
# folders listed in config_updater.json "extra_folders" go to a second file (manifest-extra.json, read by
# 2.1.3+ through "extra_manifests") and manifest.json stays installable by every launcher. The file list is
# unchanged, and
# every entry is validated with the launcher's own rules (luminary_app.modpack.safe_relpath /
# parse_manifest) before anything is written: one bad name (trailing space, CON.jar, "..", a case-only
# duplicate) would otherwise make every launcher reject the whole manifest.
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE_DIR))

from luminary_app import modpack  # noqa: E402  (same rules as the launcher that consumes the manifest)

CONFIG_FILE = BASE_DIR / "config_updater.json"
SKIP_NAMES = {"thumbs.db", "desktop.ini", ".ds_store"}
SKIP_SUFFIXES = (".part", ".tmp", ".crdownload")


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(mc_dir: Path, sync_folders, version: str | None = None):
    """Returns (manifest dict, problems, skipped). The manifest is only valid if problems is empty.

    sync_folders is a folder -> mode dict (or a tuple of names, which get the default modes)."""
    rules = sync_folders if isinstance(sync_folders, dict) else modpack.folder_rules(sync_folders)
    sync_folders = tuple(rules)
    files, problems, skipped, seen = [], [], [], {}
    for folder in sync_folders:
        root = mc_dir / folder
        if not root.is_dir():
            skipped.append(f"{folder}/ (папки нет)")
            continue
        for dirpath, dirnames, names in os.walk(root):
            # servarium does not serve hidden files or links: listing one would make every update fail
            for d in sorted(dirnames):
                rel = (Path(dirpath) / d).relative_to(mc_dir).as_posix()
                if d.startswith("."):
                    skipped.append(f"{rel}/ (скрытая папка: servarium такие не раздаёт)")
                elif (Path(dirpath) / d).is_symlink():
                    problems.append(f"{rel}: ссылка на папку (положи саму папку)")
            dirnames[:] = sorted(d for d in dirnames if not d.startswith(".") and not (Path(dirpath) / d).is_symlink())
            for name in sorted(names):
                full = Path(dirpath) / name
                rel = full.relative_to(mc_dir).as_posix()
                if name.lower() in SKIP_NAMES or name.lower().endswith(SKIP_SUFFIXES):
                    skipped.append(rel)
                    continue
                if name.startswith("."):
                    skipped.append(f"{rel} (скрытый файл: servarium такие не раздаёт)")
                    continue
                if full.is_symlink():
                    problems.append(f"{rel}: символическая ссылка (положи сам файл)")
                    continue
                try:
                    rel = modpack.safe_relpath(rel, sync_folders)
                except modpack.PackError as exc:
                    problems.append(f"{rel}: {exc} (лаунчер отклонит такой манифест)")
                    continue
                key = rel.lower()
                if key in seen:
                    problems.append(f"{rel}: отличается от {seen[key]} только регистром (на Windows это один файл)")
                    continue
                seen[key] = rel
                size = full.stat().st_size
                if size > modpack.MAX_FILE_SIZE:
                    problems.append(f"{rel}: файл больше {modpack.MAX_FILE_SIZE // 1024 ** 3} ГБ")
                    continue
                files.append({"path": rel, "sha256": sha256_of_file(full), "size": size})
    now = datetime.now()
    manifest = {"version": version or now.strftime("%Y.%m.%d-%H%M"),
                "updated": now.isoformat(timespec="seconds"), "folders": dict(rules), "files": files}
    if not problems:
        modpack.parse_manifest(manifest, sync_folders)  # exactly what the launcher will run
    return manifest, problems, skipped


def check_local(manifest: dict, mc_dir: Path, sync_folders) -> list[str]:
    """Every listed file exists with the listed size and hash, and nothing unlisted would be removed."""
    parsed = modpack.parse_manifest(manifest, sync_folders)
    mirrored = [f for f, mode in parsed.folders.items() if mode == modpack.MIRROR]
    errors = []
    listed = set()
    for f in parsed.files:
        listed.add(f.path.lower())
        p = mc_dir / f.path
        if not p.is_file():
            errors.append(f"нет файла: {f.path}")
        elif p.stat().st_size != f.size:
            errors.append(f"размер не совпадает: {f.path}")
        elif sha256_of_file(p) != f.sha256:
            errors.append(f"хеш не совпадает: {f.path}")
    for folder in mirrored:  # update folders keep unlisted files, so only mirror folders can lose any
        root = mc_dir / folder
        if root.is_dir():
            for p in sorted(root.rglob("*")):
                rel = p.relative_to(mc_dir).as_posix()
                if p.is_file() and rel.lower() not in listed and p.name.lower() not in SKIP_NAMES:
                    errors.append(f"не в манифесте (у игроков будет убран в .backup): {rel}")
    return errors


def check_remote(cfg: modpack.PackConfig, timeout: float = 60) -> tuple[list[str], int]:
    """Download the published manifest and every file, verifying size and SHA-256. Returns (errors, n)."""
    manifest = modpack.fetch_manifest(cfg)  # raises PackError, e.g. when manifest.json is missing (404)
    base = cfg.server_url.rstrip("/") + "/"
    errors = []
    for f in manifest.files:
        if f.size == 0:
            continue  # the launcher creates empty files itself; servarium refuses to store them
        url = base + urllib.parse.quote(f.path, safe="/")
        h, size = hashlib.sha256(), 0
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "LuminaryManifestCheck"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                for chunk in iter(lambda: r.read(1 << 20), b""):
                    h.update(chunk)
                    size += len(chunk)
        except urllib.error.HTTPError as exc:
            errors.append(f"{f.path}: HTTP {exc.code}")
            continue
        except (urllib.error.URLError, OSError) as exc:
            errors.append(f"{f.path}: {getattr(exc, 'reason', exc)}")
            continue
        if size != f.size:
            errors.append(f"{f.path}: размер {size}, в манифесте {f.size}")
        elif h.hexdigest() != f.sha256:
            errors.append(f"{f.path}: хеш не совпадает")
    return errors, len(manifest.files)


EXTRA_NAME = "manifest-extra.json"


def split_rules(cfg: modpack.PackConfig, extra_folders) -> tuple[dict, dict]:
    """(main manifest rules, extra manifest rules): extra_folders move to the extra manifest."""
    extra = [f for f in (extra_folders or []) if f in cfg.folders]
    unknown = [f for f in (extra_folders or []) if f not in cfg.folders]
    if unknown:
        sys.exit(f"!!! extra_folders не входят в sync_folders: {', '.join(unknown)}")
    main_rules = {f: m for f, m in cfg.folders.items() if f not in extra}
    return main_rules, {f: cfg.folders[f] for f in extra}


def _load_cfg(path: Path) -> modpack.PackConfig:
    cfg = modpack.load_config(path)
    if cfg is None:
        sys.exit(f"!!! Не найден {path}")
    if not cfg.sync_folders:
        sys.exit("!!! В config_updater.json пустой sync_folders")
    return cfg


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Сборка и проверка manifest.json для сервера сборки")
    ap.add_argument("--config", type=Path, default=CONFIG_FILE)
    ap.add_argument("--mc-dir", type=Path, default=BASE_DIR / ".minecraft")
    ap.add_argument("--out", type=Path, default=BASE_DIR / "manifest.json")
    ap.add_argument("--version", help="строка версии (по умолчанию дата и время)")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="сверить --out с локальными файлами")
    mode.add_argument("--check-remote", action="store_true", help="скачать опубликованную сборку и проверить")
    args = ap.parse_args(argv)
    cfg = _load_cfg(args.config)
    extra_folders = json.loads(args.config.read_text(encoding="utf-8")).get("extra_folders") or []
    main_rules, extra_rules = split_rules(cfg, extra_folders)
    extra_path = args.out.with_name(EXTRA_NAME)

    if args.check_remote:
        print(f"Проверка опубликованной сборки: {cfg.manifest_url}")
        try:
            errors, n = check_remote(cfg)
        except modpack.PackError as exc:
            print(f"!!! {exc}")
            return 1
        for e in errors:
            print("  ОШИБКА", e)
        print(f"Файлов: {n}, ошибок: {len(errors)}")
        return 1 if errors else 0

    if args.check:
        manifest = json.loads(args.out.read_text(encoding="utf-8"))
        try:
            errors = check_local(manifest, args.mc_dir, cfg.folders)
            for name in modpack.extra_names(manifest):
                errors += check_local(json.loads(args.out.with_name(name).read_text(encoding="utf-8")),
                                      args.mc_dir, cfg.folders)
        except modpack.PackError as exc:
            print(f"!!! Манифест некорректен: {exc}")
            return 1
        for e in errors:
            print("  ОШИБКА", e)
        print("Манифест совпадает с файлами" if not errors else f"Ошибок: {len(errors)}")
        return 1 if errors else 0

    print(f"Папка .minecraft: {args.mc_dir}")
    print("Синхронизируются: " + ", ".join(f"{f} ({m})" for f, m in main_rules.items()))
    manifest, problems, skipped = build_manifest(args.mc_dir, main_rules, args.version)
    extra = None
    if extra_rules:
        print(f"В {EXTRA_NAME} (лаунчеры 2.1.3+): " + ", ".join(f"{f} ({m})" for f, m in extra_rules.items()))
        extra, extra_problems, extra_skipped = build_manifest(args.mc_dir, extra_rules, manifest["version"])
        problems += extra_problems
        skipped += extra_skipped
        manifest["extra_manifests"] = [EXTRA_NAME]
    for s in skipped:
        print("  [пропуск]", s)
    if problems:
        for p in problems:
            print("  [ОШИБКА]", p)
        print(f"!!! manifest.json НЕ записан: исправь {len(problems)} файл(ов) и запусти снова.")
        return 1
    for data, path in ((extra, extra_path), (manifest, args.out)):
        if data is None:
            continue
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, path)
        total = sum(f["size"] for f in data["files"])
        print(f"Готово: версия {data['version']}, файлов {len(data['files'])}, {total / 1024 / 1024:.2f} МБ -> {path}")
    print("Дальше: загрузи файлы из .minecraft/<папки>, затем " + (f"{EXTRA_NAME} и " if extra else "") +
          "manifest.json (последним) на сервер, затем\n"
          "        python\\python.exe make_manifest.py --check-remote")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
