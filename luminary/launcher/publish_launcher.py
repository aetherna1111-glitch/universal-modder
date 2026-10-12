# publish_launcher.py - publish a launcher update for all installed launchers (owner tool).
#
#   python\python.exe publish_launcher.py keygen --out C:\keys\luminary-update.key
#   python\python.exe publish_launcher.py publish . out\launcher --key C:\keys\luminary-update.key --notes "Что нового"
#   python\python.exe publish_launcher.py verify https://.../launcher/
#
# 1. Raise __version__ in luminary_app/__init__.py (players only update to a *higher* version).
# 2. `publish` writes out\launcher\launcher.json (signed) and out\launcher\<version>\... (the files).
# 3. Upload the <version> folder first, then launcher.json, to the folder in profiles.json ->
#    launcher_update.url. Launchers check it at start and every 6 hours, download, verify the signature and
#    every file, and install on restart (with automatic rollback if the new version fails to start).
# Keep the key file secret and backed up: whoever has it can publish code to every player.
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from luminary_app import ed25519, profiles, selfupdate  # noqa: E402


def read_version(source: Path) -> str:
    text = (source / "luminary_app" / "__init__.py").read_text(encoding="utf-8")
    m = re.search(r'__version__\s*=\s*"([^"]+)"', text)
    if not m:
        raise SystemExit("не найден __version__ в luminary_app/__init__.py")
    selfupdate.parse_version(m.group(1))
    return m.group(1)


def load_key(path: Path) -> bytes:
    try:
        secret = bytes.fromhex(path.read_text(encoding="ascii").strip())
    except (OSError, ValueError):
        raise SystemExit(f"не удалось прочитать ключ {path}")
    if len(secret) != 32:
        raise SystemExit("ключ повреждён (ожидается 64 hex-символа)")
    return secret


def cmd_keygen(args) -> int:
    out = Path(args.out)
    if out.exists():
        print(f"!!! {out} уже существует — не перезаписываю (это сломало бы обновления у игроков)")
        return 1
    out.parent.mkdir(parents=True, exist_ok=True)
    secret = ed25519.generate_secret()
    fd = os.open(str(out), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as f:
        f.write(secret.hex() + "\n")
    pub = ed25519.public_key(secret).hex()
    print(f"Секретный ключ: {out}  (храни в тайне и сделай резервную копию)")
    print("Впиши в profiles.json:")
    print(json.dumps({"launcher_update": {"url": "https://<хост>/launcher/", "public_key": pub}}, indent=2))
    return 0


def cmd_publish(args) -> int:
    source, out = Path(args.source), Path(args.out)
    secret = load_key(Path(args.key))
    version = read_version(source)
    try:
        catalog = profiles.load_catalog(source / "profiles.json")
    except (profiles.ProfileError, ValueError, OSError) as exc:
        print(f"!!! profiles.json: {exc}")
        return 1
    pub = ed25519.public_key(secret)
    if catalog.update_key != pub:
        print("!!! public_key в profiles.json не совпадает с этим ключом: после такого обновления лаунчеры "
              "игроков отклоняли бы следующие. Впиши ключ из `keygen` в profiles.json.")
        return 1
    files = selfupdate.collect_files(source)
    names = {f.path for f in files}
    if "launcher.py" not in names or "luminary_app/main.py" not in names:
        print("!!! в папке нет программы лаунчера")
        return 1
    published = datetime.now().isoformat(timespec="seconds")
    manifest = selfupdate.make_manifest(version, files, secret, args.notes or "", published)
    selfupdate.parse_release(manifest, pub)  # exactly what the launchers will run
    target = out / version
    if target.exists():
        shutil.rmtree(target)
    for f in files:
        dst = target / f.path
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / f.path, dst)
    (out / selfupdate.MANIFEST).write_bytes(manifest)
    size = sum(f.size for f in files) / 1024 / 1024
    print(f"Версия {version}: {len(files)} файлов, {size:.1f} МБ -> {out}")
    print(f"Загрузи папку {version}\\ и затем {selfupdate.MANIFEST} в {catalog.update_url or '<launcher_update.url>'}")
    print("Проверка после загрузки: python\\python.exe publish_launcher.py verify <url>")
    return 0


def cmd_verify(args) -> int:
    catalog = profiles.load_catalog(HERE / "profiles.json")
    key = bytes.fromhex(args.public_key) if args.public_key else catalog.update_key
    url = args.url or catalog.update_url
    if not url or len(key) != 32:
        print("!!! нужен url и public_key (в profiles.json или аргументами)")
        return 1
    cfg = selfupdate.UpdateConfig(url, key)
    try:
        raw = urllib.request.urlopen(cfg.manifest_url, timeout=30).read()
        release = selfupdate.parse_release(raw, key)
    except Exception as exc:  # noqa: BLE001
        print(f"!!! {cfg.manifest_url}: {exc}")
        return 1
    errors = 0
    for f in release.files:
        try:
            data = urllib.request.urlopen(cfg.file_url(release.version, f.path), timeout=60).read()
        except Exception as exc:  # noqa: BLE001
            print(f"  ОШИБКА {f.path}: {exc}")
            errors += 1
            continue
        if len(data) != f.size or hashlib.sha256(data).hexdigest() != f.sha256:
            print(f"  ОШИБКА {f.path}: контрольная сумма")
            errors += 1
    print(f"Опубликована версия {release.version}, подпись верна, файлов {len(release.files)}, ошибок {errors}")
    return 1 if errors else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Публикация обновлений лаунчера")
    sub = ap.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen")
    k.add_argument("--out", required=True)
    p = sub.add_parser("publish")
    p.add_argument("source")
    p.add_argument("out")
    p.add_argument("--key", required=True)
    p.add_argument("--notes", default="")
    v = sub.add_parser("verify")
    v.add_argument("url", nargs="?")
    v.add_argument("--public-key")
    args = ap.parse_args(argv)
    return {"keygen": cmd_keygen, "publish": cmd_publish, "verify": cmd_verify}[args.cmd](args)


if __name__ == "__main__":
    raise SystemExit(main())
