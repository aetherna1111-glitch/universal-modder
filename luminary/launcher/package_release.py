# package_release.py - assemble and verify the Windows distribution of the launcher (developer tool).
#
#   python package_release.py <prepared launcher folder> <output folder> --version 2.0.0 [--zip]
#
# The prepared folder is this directory plus the binaries that are not in git: python\ (portable 3.11
# with PySide6 and mcstatus), jre\ (Java 17+), LuminaryLauncher.exe (PortableMC 5.0.5) and Luminary.exe
# (from build_starter.bat). The tool refuses to package when something a player needs is missing, when
# an executable would open a console, when profiles.json points at a development or plain-http account
# server, or when user data, logs, sessions, machine-specific paths or key material would be shipped.
# The zip is byte-for-byte reproducible: same input, same SHA-256.
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from luminary_app import modpack, profiles  # noqa: E402

GUI, CONSOLE = 2, 3
REQUIRED_FILES = ["launcher.py", "profiles.json", "config_updater.json", "luminary.ico", "run.bat",
                  "Luminary.exe", "LuminaryLauncher.exe", "python/pythonw.exe", "python/python.exe",
                  "jre/bin/javaw.exe"]
REQUIRED_DIRS = ["luminary_app", "assets", "sounds", "python/Lib/site-packages/PySide6",
                 "python/Lib/site-packages/mcstatus"]
EXPECTED_SUBSYSTEM = {"Luminary.exe": GUI, "python/pythonw.exe": GUI, "jre/bin/javaw.exe": GUI,
                      "LuminaryLauncher.exe": CONSOLE}  # PortableMC is a console program; the launcher hides it
# Never shipped: per-player data, caches, development files.
EXCLUDE_TOP = {"launcher_data", "logs", "runtime", ".minecraft", "tests", "starter", "installer", ".git", ".github",
               ".pytest_cache", "nickname.txt", "ram.txt", "manifest.json", "make_manifest.py",
               "package_release.py", "publish_launcher.py", "build_starter.bat", ".gitignore", "session.bin"}
EXCLUDE_ANY = {"__pycache__", ".DS_Store", "Thumbs.db", "desktop.ini"}
EXCLUDE_SUFFIX = {".pyc", ".pyo", ".log", ".tmp", ".part"}
# Only our own code/config is scanned (python/ and jre/ are third-party distributions).
SCAN_ROOTS = ("launcher.py", "luminary_app", "profiles.json", "config_updater.json", "run.bat",
              "run-debug.bat", "README.md")
MACHINE_PATH = re.compile(r"(/home/[a-z0-9_.-]+/|/tmp/|/Users/[A-Za-z0-9_.-]+/|[A-Za-z]:\\\\?Users\\\\?(?!name\b)[A-Za-z0-9_.-]+)")
SECRET = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----|\"access_token\"\s*:\s*\"[0-9a-f]{32,}\"")
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class ReleaseError(Exception):
    pass


def pe_subsystem(path: Path) -> int:
    with open(path, "rb") as f:
        data = f.read(4096)
    if data[:2] != b"MZ":
        raise ReleaseError(f"{path.name}: не Windows-программа")
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ReleaseError(f"{path.name}: повреждённый PE-заголовок")
    return struct.unpack_from("<H", data, pe + 24 + 68)[0]


def excluded(rel: PurePosixPath) -> bool:
    if rel.parts[0] in EXCLUDE_TOP:
        return True
    if any(p in EXCLUDE_ANY for p in rel.parts):
        return True
    return rel.suffix.lower() in EXCLUDE_SUFFIX


def validate(src: Path, allow_dev_api: bool = False) -> list[str]:
    errors = []
    for f in REQUIRED_FILES:
        if not (src / f).is_file():
            errors.append(f"нет файла {f}")
    for d in REQUIRED_DIRS:
        if not (src / d).is_dir():
            errors.append(f"нет папки {d}")
    for f, want in EXPECTED_SUBSYSTEM.items():
        p = src / f
        if p.is_file():
            try:
                got = pe_subsystem(p)
            except ReleaseError as exc:
                errors.append(str(exc))
                continue
            if got != want:
                kind = {GUI: "GUI", CONSOLE: "консольная"}
                errors.append(f"{f}: подсистема {kind.get(got, got)}, ожидалась {kind[want]}"
                              + (" (соберите Luminary.exe через build_starter.bat)" if f == "Luminary.exe" else ""))
    try:
        cat = profiles.load_catalog(src / "profiles.json")
        if cat.warnings:
            errors.extend(f"profiles.json: {w}" for w in cat.warnings)
        if cat.api_url and not allow_dev_api and not cat.api_url.startswith("https://"):
            errors.append(f"profiles.json: сервер аккаунтов {cat.api_url} — адрес для разработки, нужен https")
        if cat.accounts_required and not cat.api_url:
            errors.append("profiles.json: accounts.required без api_url")
        if cat.api_url and cat.authlib is None:
            errors.append("profiles.json: аккаунты включены, но authlib_injector не задан")
        for p in cat.profiles:
            if p.modpack_config and not (src / p.modpack_config).is_file():
                errors.append(f"profiles.json: профиль {p.id} ссылается на отсутствующий {p.modpack_config}")
    except (profiles.ProfileError, ValueError, OSError) as exc:
        errors.append(f"profiles.json: {exc}")
    try:
        if modpack.load_config(src / "config_updater.json") is None:
            errors.append("нет config_updater.json")
    except (modpack.PackError, ValueError, OSError) as exc:
        errors.append(f"config_updater.json: {exc}")
    for root in SCAN_ROOTS:
        base = src / root
        files = [base] if base.is_file() else sorted(base.rglob("*")) if base.is_dir() else []
        for f in files:
            if not f.is_file() or f.suffix.lower() not in {".py", ".json", ".bat", ".md", ".txt", ""}:
                continue
            if "__pycache__" in f.parts:
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            rel = f.relative_to(src).as_posix()
            if SECRET.search(text):
                errors.append(f"{rel}: похоже на ключ или токен")
            m = MACHINE_PATH.search(text)
            if m:
                errors.append(f"{rel}: путь с конкретного компьютера: {m.group(0)}")
    return errors


def collect(src: Path) -> list[PurePosixPath]:
    out = []
    for p in sorted(src.rglob("*")):
        rel = PurePosixPath(p.relative_to(src).as_posix())
        if p.is_file() and not p.is_symlink() and not excluded(rel):
            out.append(rel)
    return out


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def package(src: Path, out: Path, version: str, make_zip: bool = False, allow_dev_api: bool = False) -> dict:
    errors = validate(src, allow_dev_api)
    if errors:
        raise ReleaseError("\n".join(errors))
    if not re.fullmatch(r"[0-9A-Za-z.+-]{1,32}", version):
        raise ReleaseError("неверная строка версии")
    dest = out / "Luminary"
    if dest.exists():
        shutil.rmtree(dest)
    files = collect(src)
    sums = []
    for rel in files:
        target = dest / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src / rel, target)
        sums.append(f"{sha256(target)}  {rel}")
    info = {"product": "Luminary Launcher", "version": version, "files": len(files)}
    (dest / "release-info.json").write_text(json.dumps(info, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (dest / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")
    result = {"folder": str(dest), "files": len(files)}
    if make_zip:
        zpath = out / f"Luminary-{version}.zip"
        entries = sorted([*files, PurePosixPath("release-info.json"), PurePosixPath("SHA256SUMS.txt")])
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for rel in entries:
                zi = zipfile.ZipInfo(f"Luminary/{rel}", date_time=ZIP_TIME)
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.external_attr = 0o644 << 16
                zi.create_system = 0
                z.writestr(zi, (dest / rel).read_bytes())
        result["zip"] = str(zpath)
        result["zip_sha256"] = sha256(zpath)
    return result


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Собрать и проверить дистрибутив лаунчера для Windows")
    ap.add_argument("source", type=Path, help="подготовленная папка лаунчера (с python, jre, exe)")
    ap.add_argument("out", type=Path, help="куда положить Luminary/ и zip")
    ap.add_argument("--version", required=True)
    ap.add_argument("--zip", action="store_true", help="также создать воспроизводимый zip")
    ap.add_argument("--check", action="store_true", help="только проверить, ничего не копировать")
    ap.add_argument("--allow-dev-api", action="store_true", help="разрешить http://localhost (тестовые сборки)")
    args = ap.parse_args(argv)
    if args.check:
        errors = validate(args.source, args.allow_dev_api)
        for e in errors:
            print("ОШИБКА", e)
        print("Готово к упаковке" if not errors else f"Ошибок: {len(errors)}")
        return 1 if errors else 0
    try:
        r = package(args.source, args.out, args.version, args.zip, args.allow_dev_api)
    except ReleaseError as exc:
        for line in str(exc).splitlines():
            print("ОШИБКА", line)
        return 1
    print(f"Дистрибутив: {r['folder']} ({r['files']} файлов, SHA256SUMS.txt внутри)")
    if "zip" in r:
        print(f"Архив: {r['zip']}\nSHA-256: {r['zip_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
