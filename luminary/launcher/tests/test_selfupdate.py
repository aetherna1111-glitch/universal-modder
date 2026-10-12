"""Launcher self-update end to end: publish (owner) -> local update host -> check/stage -> apply on start."""
import json
import re
import shutil
import subprocess
import sys
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

import publish_launcher
from conftest import ROOT
from luminary_app import ed25519, selfupdate
from luminary_app.selfupdate import FileEntry, UpdateConfig, UpdateError

APP_FILES = ("launcher.py", "luminary_app", "assets", "sounds", "luminary.ico", "config_updater.json",
             "profiles.json", "run.bat", "run-debug.bat", "README.md")


class _Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


@pytest.fixture
def host(tmp_path):
    root = tmp_path / "host"
    root.mkdir()
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), partial(_Quiet, directory=str(root)))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield root, f"http://127.0.0.1:{httpd.server_address[1]}/launcher/"
    httpd.shutdown()


def make_tree(dst: Path, version: str, url: str, pub: bytes) -> Path:
    dst.mkdir(parents=True)
    for name in APP_FILES:
        src = ROOT / name
        if src.is_dir():
            shutil.copytree(src, dst / name, ignore=shutil.ignore_patterns("__pycache__"))
        else:
            shutil.copy(src, dst / name)
    set_version(dst, version)
    data = json.loads((dst / "profiles.json").read_text(encoding="utf-8"))
    data["launcher_update"] = {"url": url, "public_key": pub.hex()}
    (dst / "profiles.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
    return dst


def set_version(tree: Path, version: str) -> None:
    init = tree / "luminary_app" / "__init__.py"
    init.write_text(re.sub(r'__version__ = "[^"]+"', f'__version__ = "{version}"', init.read_text()))


@pytest.fixture
def keys(tmp_path):
    key_file = tmp_path / "keys" / "update.key"
    assert publish_launcher.main(["keygen", "--out", str(key_file)]) == 0
    secret = bytes.fromhex(key_file.read_text().strip())
    return key_file, secret, ed25519.public_key(secret)


def publish(tmp_path, host_root, url, keys, version, edit=None):
    key_file, secret, pub = keys
    src = make_tree(tmp_path / f"publisher-{version}", version, url, pub)
    if edit:
        edit(src)
    assert publish_launcher.main(["publish", str(src), str(host_root / "launcher"), "--key", str(key_file),
                                  "--notes", "Тестовое обновление"]) == 0
    return src


def run_in(install: Path, code: str) -> subprocess.CompletedProcess:
    """Run Python inside the install folder the way launcher.py starts (separate process)."""
    return subprocess.run([sys.executable, "-c", f"import sys; sys.path.insert(0, {str(install)!r})\n" + code],
                          cwd=install, capture_output=True, text=True, timeout=120)


def test_update_is_published_staged_and_applied_on_start(tmp_path, host, keys):
    host_root, url = host
    _, _, pub = keys
    install = make_tree(tmp_path / "player", "2.0.0", url, pub)
    (install / "luminary_app" / "obsolete.py").write_text("OLD = True\n")
    (install / ".minecraft").mkdir()
    (install / ".minecraft" / "world.dat").write_text("player data")

    def edit(src):
        (src / "luminary_app" / "new_feature.py").write_text("NEW = True\n")
        (src / "assets" / "extra.png").write_bytes(b"\x89PNG fake")

    publish(tmp_path, host_root, url, keys, "2.0.1", edit)
    cfg = UpdateConfig(url, pub)
    release = selfupdate.check(cfg, install, "2.0.0")
    assert release is not None and release.version == "2.0.1" and release.notes == "Тестовое обновление"
    staged = selfupdate.stage(cfg, install, release)
    # only what differs is downloaded: version file, new module, new asset (+ profiles.json/launcher.py same)
    assert staged.changed == 3 and staged.deleted == 1
    assert selfupdate.pending_version(install) == "2.0.1"
    # nothing is replaced while the old version runs
    assert 'version__ = "2.0.0"' in (install / "luminary_app" / "__init__.py").read_text()

    r = run_in(install, "import launcher; launcher._apply_pending_update()\n"
                        "import luminary_app; print(luminary_app.__version__)")
    assert r.returncode == 0, r.stderr
    assert r.stdout.strip() == "2.0.1"
    assert (install / "luminary_app" / "new_feature.py").exists()
    assert not (install / "luminary_app" / "obsolete.py").exists()
    assert (install / ".minecraft" / "world.dat").read_text() == "player data"
    assert selfupdate.has_unconfirmed(install) and selfupdate.pending_version(install) is None
    selfupdate.confirm_ok(install)
    assert not selfupdate.has_unconfirmed(install)
    assert selfupdate.check(cfg, install, "2.0.1") is None  # up to date


def test_broken_release_rolls_back_and_is_not_offered_again(tmp_path, host, keys):
    host_root, url = host
    _, _, pub = keys
    install = make_tree(tmp_path / "player", "2.0.0", url, pub)
    publish(tmp_path, host_root, url, keys, "2.0.1",
            lambda src: (src / "luminary_app" / "main.py").write_text("def main(:\n    pass\n"))
    cfg = UpdateConfig(url, pub)
    selfupdate.stage(cfg, install, selfupdate.check(cfg, install, "2.0.0"))
    r = run_in(install,
               "import launcher\nlauncher._apply_pending_update()\n"
               "try:\n    import luminary_app.main\nexcept SyntaxError as exc:\n    print('rolled', launcher._rollback_update(exc))\n"
               "import luminary_app.main, luminary_app; print(luminary_app.__version__)")
    assert r.returncode == 0, r.stderr
    assert r.stdout.split() == ["rolled", "True", "2.0.0"]
    assert "2.0.1" in selfupdate.bad_versions(install)
    assert selfupdate.check(cfg, install, "2.0.0") is None


def test_forged_tampered_or_foreign_releases_are_refused(tmp_path, host, keys):
    host_root, url = host
    _, secret, pub = keys
    publish(tmp_path, host_root, url, keys, "2.0.1")
    manifest = host_root / "launcher" / "launcher.json"
    env = json.loads(manifest.read_text())
    payload = json.loads(__import__("base64").b64decode(env["payload"]))
    payload["version"] = "9.9.9"
    env["payload"] = __import__("base64").b64encode(json.dumps(payload).encode()).decode()
    with pytest.raises(UpdateError, match="подпись"):
        selfupdate.parse_release(json.dumps(env).encode(), pub)
    # a release signed by somebody else's key
    other = ed25519.generate_secret()
    files = [FileEntry("launcher.py", "0" * 64, 1), FileEntry("luminary_app/main.py", "0" * 64, 1)]
    with pytest.raises(UpdateError, match="подпись"):
        selfupdate.parse_release(selfupdate.make_manifest("9.9.9", files, other), pub)
    with pytest.raises(UpdateError):
        selfupdate.parse_release(b"<html>maintenance</html>", pub)


@pytest.mark.parametrize("bad", ["python/python311.dll", "jre/bin/java.exe", "../evil.py", "luminary_app/../x.py",
                                 "LuminaryLauncher.exe", ".minecraft/mods/x.jar", "C:/Windows/x.dll",
                                 "luminary_app/__pycache__/x.pyc", "launcher_data/session.bin"])
def test_signed_releases_still_cannot_touch_other_files(keys, bad):
    _, secret, pub = keys
    files = [FileEntry("launcher.py", "0" * 64, 1), FileEntry("luminary_app/main.py", "0" * 64, 1),
             FileEntry(bad, "0" * 64, 1)]
    with pytest.raises(UpdateError):
        selfupdate.parse_release(selfupdate.make_manifest("2.0.1", files, secret), pub)


def test_no_downgrade_and_nothing_published(tmp_path, host, keys):
    host_root, url = host
    _, _, pub = keys
    install = make_tree(tmp_path / "player", "2.0.5", url, pub)
    cfg = UpdateConfig(url, pub)
    assert selfupdate.check(cfg, install, "2.0.5") is None          # 404: nothing published yet
    publish(tmp_path, host_root, url, keys, "2.0.1")
    assert selfupdate.check(cfg, install, "2.0.5") is None          # older signed release: ignored
    assert selfupdate.is_newer("2.0.10", "2.0.9") and not selfupdate.is_newer("2.0", "2.0.0")


def test_corrupted_file_on_host_is_refused_and_nothing_is_pending(tmp_path, host, keys):
    host_root, url = host
    _, _, pub = keys
    install = make_tree(tmp_path / "player", "2.0.0", url, pub)
    publish(tmp_path, host_root, url, keys, "2.0.1")
    (host_root / "launcher" / "2.0.1" / "luminary_app" / "__init__.py").write_text("print('hacked')\n")
    cfg = UpdateConfig(url, pub)
    with pytest.raises(UpdateError, match="контрольная сумма"):
        selfupdate.stage(cfg, install, selfupdate.check(cfg, install, "2.0.0"))
    assert selfupdate.pending_version(install) is None


def test_failed_apply_restores_everything_and_retries(tmp_path, host, keys):
    host_root, url = host
    _, _, pub = keys
    install = make_tree(tmp_path / "player", "2.0.0", url, pub)
    publish(tmp_path, host_root, url, keys, "2.0.1",
            lambda src: (src / "assets" / "blocked.png").write_bytes(b"new"))
    cfg = UpdateConfig(url, pub)
    selfupdate.stage(cfg, install, selfupdate.check(cfg, install, "2.0.0"))
    (install / "assets" / "blocked.png").mkdir()  # cannot be replaced (stands in for a locked file)
    before = (install / "luminary_app" / "__init__.py").read_text()
    assert selfupdate.apply_pending(install, "2.0.0") is None
    assert (install / "luminary_app" / "__init__.py").read_text() == before
    assert selfupdate.pending_version(install) == "2.0.1"           # tried again next start
    for _ in range(selfupdate.MAX_ATTEMPTS):
        selfupdate.apply_pending(install, "2.0.0")
    assert selfupdate.pending_version(install) is None and "2.0.1" in selfupdate.bad_versions(install)


def test_publish_refuses_a_key_that_players_would_reject(tmp_path, host, keys):
    host_root, url = host
    key_file, _, _ = keys
    other_pub = ed25519.public_key(ed25519.generate_secret())
    src = make_tree(tmp_path / "publisher", "2.0.1", url, other_pub)
    assert publish_launcher.main(["publish", str(src), str(host_root / "launcher"), "--key", str(key_file)]) == 1
    assert not (host_root / "launcher" / "launcher.json").exists()
    assert publish_launcher.main(["keygen", "--out", str(key_file)]) == 1  # never overwrites a key


def test_ui_service_stages_update_and_offers_restart(tmp_path, host, keys, monkeypatch):
    import os
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    pytest.importorskip("PySide6.QtWidgets")
    from test_ui import app, make_window, wait_until  # noqa: F401
    from luminary_app import __version__, profiles
    from luminary_app.paths import Paths
    from PySide6.QtWidgets import QApplication
    QApplication.instance() or QApplication([])
    host_root, url = host
    _, _, pub = keys
    newer = ".".join(str(int(x) + (1 if i == 2 else 0)) for i, x in enumerate(__version__.split(".")))
    publish(tmp_path, host_root, url, keys, newer)
    install = make_tree(tmp_path / "player", __version__, url, pub)
    paths = Paths(install)
    paths.ensure()
    catalog = profiles.load_catalog(install / "profiles.json")
    from luminary_app.ui import main_window
    offered = []
    monkeypatch.setattr(main_window.MainWindow, "on_update_staged", lambda self, staged: offered.append(staged.version))
    win, state = make_window(paths, sys.executable, catalog)
    win.updates.staged.disconnect()
    win.updates.staged.connect(lambda s: offered.append(s.version))
    win.updates.check(manual=True)
    assert wait_until(lambda: offered, 30), win.updates.last_status
    assert offered == [newer] and selfupdate.pending_version(install) == newer
    assert newer in win.settings_page.update_status.text()
    win.close(); state.runner.shutdown()


def test_empty_files_are_created_locally_not_downloaded(tmp_path, host, keys):
    """servarium refuses to store empty files; launchers must not depend on them being on the host."""
    host_root, url = host
    key_file, secret, pub = keys
    install = make_tree(tmp_path / "player", "2.0.0", url, pub)
    src = make_tree(tmp_path / "publisher", "2.0.1", url, pub)
    (src / "luminary_app" / "empty_marker.py").write_bytes(b"")
    # publish by hand (the tool refuses empty files on purpose), then drop the empty file from the host
    files = selfupdate.collect_files(src)
    target = host_root / "launcher" / "2.0.1"
    for f in files:
        (target / f.path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src / f.path, target / f.path)
    (host_root / "launcher" / "launcher.json").write_bytes(selfupdate.make_manifest("2.0.1", files, secret))
    (target / "luminary_app" / "empty_marker.py").unlink()
    cfg = UpdateConfig(url, pub)
    selfupdate.stage(cfg, install, selfupdate.check(cfg, install, "2.0.0"))
    assert selfupdate.apply_pending(install, "2.0.0") == "2.0.1"
    marker = install / "luminary_app" / "empty_marker.py"
    assert marker.is_file() and marker.stat().st_size == 0
    assert publish_launcher.main(["verify", url, "--public-key", pub.hex()]) == 0  # empty file not required


def test_publish_refuses_empty_files(tmp_path, host, keys):
    host_root, url = host
    key_file, _, pub = keys
    src = make_tree(tmp_path / "publisher", "2.0.1", url, pub)
    (src / "luminary_app" / "blank.py").write_bytes(b"")
    assert publish_launcher.main(["publish", str(src), str(host_root / "launcher"), "--key", str(key_file)]) == 1
    assert not (host_root / "launcher" / "launcher.json").exists()


def test_shipped_launcher_has_no_empty_files():
    empty = [p.relative_to(ROOT).as_posix() for d in ("luminary_app", "assets", "sounds")
             for p in (ROOT / d).rglob("*") if p.is_file() and p.stat().st_size == 0 and "__pycache__" not in p.parts]
    assert empty == []
