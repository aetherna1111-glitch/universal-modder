"""UI integration tests (headless Qt): navigation, validation, and the whole Play pipeline with a fake
PortableMC and a fake Java, a local modpack server and (for account mode) the real backend."""
import hashlib
import json
import os
import shutil
import stat
import sys
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
pytest.importorskip("PySide6.QtWidgets")
from PySide6.QtCore import QCoreApplication, QEventLoop, QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from conftest import FIXTURES, ROOT  # noqa: E402
from luminary_app import launch, profiles, settings as settings_mod  # noqa: E402
from luminary_app.paths import Paths  # noqa: E402


@pytest.fixture(scope="module")
def app():
    a = QApplication.instance() or QApplication([])
    from luminary_app.ui import theme
    a.setStyleSheet(theme.stylesheet())
    return a


def wait_until(cond, timeout=20.0):
    end = time.time() + timeout
    while time.time() < end:
        QCoreApplication.processEvents(QEventLoop.AllEvents, 50)
        if cond():
            return True
        time.sleep(0.01)
    return False


def exe_script(path: Path, target: Path) -> Path:
    path.write_text(f"#!{sys.executable}\nimport runpy, sys\nsys.argv[0] = {str(target)!r}\n"
                    f"runpy.run_path({str(target)!r}, run_name='__main__')\n")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


class _Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


@pytest.fixture
def pack_server(tmp_path):
    root = tmp_path / "srv"
    (root / "pack" / "mods").mkdir(parents=True)
    data = b"MODJAR" * 100
    (root / "pack" / "mods" / "luminary-core.jar").write_bytes(data)
    (root / "pack" / "manifest.json").write_text(json.dumps({"version": "2026.10.12", "files": [
        {"path": "mods/luminary-core.jar", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}]}))
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), partial(_Quiet, directory=str(root)))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}/pack/"
    httpd.shutdown()


@pytest.fixture
def install(tmp_path, monkeypatch, pack_server):
    """A launcher folder with assets, a fake PortableMC and a fake Java."""
    base = tmp_path / "Luminary"
    base.mkdir()
    for d in ("assets", "sounds"):
        shutil.copytree(ROOT / d, base / d)
    shutil.copy(ROOT / "luminary.ico", base)
    (base / "config_updater.json").write_text(json.dumps({
        "server_url": pack_server, "sync_folders": ["mods", "config"], "timeout": 10}))
    shutil.copy(ROOT / "profiles.json", base / "profiles.json")
    paths = Paths(base)
    paths.ensure()
    exe_script(paths.portablemc, FIXTURES / "fake_portablemc.py")
    java = exe_script(base / "fake-java", FIXTURES / "fake_java.py")
    monkeypatch.setenv("FAKE_PMC_ARGS_FILE", str(tmp_path / "pmc_args.txt"))
    monkeypatch.setenv("FAKE_JAVA_ARGS_FILE", str(tmp_path / "java_args.txt"))
    return paths, java, tmp_path


def make_window(paths, java, catalog=None, **settings_kw):
    from luminary_app.ui.main_window import MainWindow
    from luminary_app.ui.state import AppState
    from luminary_app.ui.tasks import TaskRunner
    from luminary_app.ui import dialogs

    class _NoSound:
        enabled = False
        played = []

        def play(self, name):
            self.played.append(name)

    s = settings_mod.Settings(java_path=str(java), after_launch="keep", pack_updates="auto", **settings_kw)
    catalog = catalog or profiles.load_catalog(paths.profiles_file)
    state = AppState(paths, catalog, s, TaskRunner(), _NoSound())
    win = MainWindow(state)
    errors = []
    dialogs.ErrorDialog.exec = lambda self: errors.append(self.windowTitle()) or 0  # record instead of blocking
    win._errors = errors
    win.service.timer.stop()
    return win, state


def test_every_sidebar_item_opens_a_real_page(app, install):
    paths, java, _ = install
    win, state = make_window(paths, java)
    win.show()
    seen = set()
    for key, button in win.nav_buttons.items():
        button.click()
        app.processEvents()
        assert win.current_page() == key and button.isChecked()
        seen.add(type(win.stack.currentWidget()).__name__)
    assert seen == {"HomeScreen", "ServerScreen", "ProfileScreen", "ModsScreen", "FriendsScreen", "SettingsScreen"}
    win.go("settings")
    assert win.current_page() == "settings"
    win.close()
    state.runner.shutdown()


def test_nickname_validation_blocks_launch(app, install):
    paths, java, _ = install
    win, state = make_window(paths, java)
    home = win.home
    home.player.nick.setText("bad nick!")
    home.on_play()
    assert not home.player.nick_hint.isHidden() and not win.controller.busy
    win.close()
    state.runner.shutdown()


def test_missing_java_reports_clearly(app, install, tmp_path):
    paths, java, _ = install
    win, state = make_window(paths, tmp_path / "nope" / "javaw.exe")
    win.home.player.nick.setText("Steve")
    win.home.on_play()
    assert wait_until(lambda: win._errors)
    assert win._errors[0] == "Не удалось запустить игру"
    win.close()
    state.runner.shutdown()


def test_offline_play_syncs_pack_installs_and_starts_game(app, install):
    paths, java, tmp = install
    win, state = make_window(paths, java)
    phases, finished = [], []
    win.controller.phase.connect(phases.append)
    win.controller.game_finished.connect(finished.append)
    win.home.player.nick.setText("Steve")
    win.home.on_play()
    assert wait_until(lambda: finished, 30), (phases, win._errors)
    assert finished[0].exit_code == 0
    assert phases[:5] == ["preparing", "syncing", "installing", "starting", "running"] and phases[-1] == "idle"
    # modpack synced and verified
    assert (paths.mc_dir / "mods" / "luminary-core.jar").read_bytes() == b"MODJAR" * 100
    # PortableMC got the 1.x arguments plus --dry and the marker
    pmc = (tmp / "pmc_args.txt").read_text().splitlines()
    assert pmc[pmc.index("--username") + 1] == "Steve"
    assert pmc[pmc.index("--uuid") + 1] == launch.java_offline_uuid("Steve")
    assert "fabric:1.20.1:0.19.5" in pmc and "--dry" in pmc
    # Java got -Xmx from the RAM setting, offline token, server quick play
    jargs = (tmp / "java_args.txt").read_text().splitlines()
    assert "-Xmx4096M" in jargs and not any(a.startswith("-javaagent") for a in jargs)
    assert jargs[jargs.index("--accessToken") + 1] == ""
    assert settings_mod.load(paths).nickname == "Steve"
    logs = list(paths.log_dir.glob("game-*.log"))
    assert logs and "fake java started" in logs[0].read_text()
    win.close()
    state.runner.shutdown()


def test_game_crash_is_reported_with_logs(app, install, monkeypatch):
    paths, java, _ = install
    monkeypatch.setenv("FAKE_JAVA_EXIT", "1")
    win, state = make_window(paths, java)
    finished = []
    win.controller.game_finished.connect(finished.append)
    win.home.player.nick.setText("Steve")
    win.home.on_play()
    assert wait_until(lambda: finished and win._errors, 30)
    assert finished[0].exit_code == 1 and finished[0].crash_report is not None
    assert win._errors[-1] == "Игра вылетела"
    win.close()
    state.runner.shutdown()


def test_settings_validation_and_persistence(app, install):
    paths, java, _ = install
    win, state = make_window(paths, java)
    sp = win.settings_page
    sp.jvm.setText("-Xmx16G")
    assert sp.jvm.property("invalid") is True
    sp._save_jvm()
    assert state.settings.jvm_args == ""
    sp.jvm.setText("-XX:+UseG1GC")
    sp._save_jvm()
    sp.sounds.setChecked(False)
    sp.java.setText("relative/java.exe")
    sp._save_java()
    assert state.settings.java_path == str(java)  # invalid path not saved
    saved = settings_mod.load(paths)
    assert saved.jvm_args == "-XX:+UseG1GC" and saved.sounds is False
    win.close()
    state.runner.shutdown()


BACKEND = ROOT.parent / "backend"


@pytest.fixture
def backend(tmp_path):
    pytest.importorskip("fastapi")
    uvicorn = pytest.importorskip("uvicorn")
    sys.path.insert(0, str(BACKEND))
    from luminary_auth import config
    from luminary_auth.app import create_app
    import socket
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    settings = config.Settings(data_dir=tmp_path / "bdata", public_url=f"http://127.0.0.1:{port}")
    server = uvicorn.Server(uvicorn.Config(create_app(settings), host="127.0.0.1", port=port, log_level="warning"))
    threading.Thread(target=server.run, daemon=True).start()
    assert wait_until(lambda: server.started, 10)
    yield f"http://127.0.0.1:{port}", settings
    server.should_exit = True


def test_account_register_skin_and_authenticated_launch(app, install, backend, tmp_path):
    from luminary_app.ui.skin_view import placeholder_skin
    paths, java, tmp = install
    url, bsettings = backend
    jar = tmp_path / "authlib-injector.jar"
    jar.write_bytes(b"pretend agent")
    data = json.loads(paths.profiles_file.read_text())
    data["accounts"]["api_url"] = url
    catalog = profiles.parse_catalog(data)
    # the catalog only accepts https; serve the pinned jar from a local file for the test
    from dataclasses import replace
    catalog = replace(catalog, authlib=replace(catalog.authlib, url=jar.as_uri(),
                                               sha256=hashlib.sha256(b"pretend agent").hexdigest()))
    win, state = make_window(paths, java, catalog)
    win.show()
    win.go("profile", "register")
    auth = win.profile.auth
    auth.r_name.setText("Diver")
    auth.r_pass.edit.setText("deep blue kelp")
    auth.r_rep.edit.setText("deep blue kelp")
    auth.register()
    assert wait_until(lambda: state.signed_in, 15)
    assert paths.session_file.exists() and b"deep blue kelp" not in paths.session_file.read_bytes()
    assert win.profile.stack.currentIndex() == 2

    # skin: pick a file, preview, save
    skin_file = tmp_path / "skin.png"
    placeholder_skin().save(str(skin_file), "PNG")
    mgr = win.profile.manager
    mgr.load_file(skin_file)
    assert mgr.pending is not None and mgr.save.isVisibleTo(mgr)
    mgr.upload()
    assert wait_until(lambda: mgr.pending is None and (state.me["profile"]["skin"]["url"] or ""), 15)

    # play with the account: game token, authlib-injector, signed-in profile
    finished = []
    win.controller.game_finished.connect(finished.append)
    win.go("home")
    win.home.on_play()
    assert wait_until(lambda: finished, 30), win._errors
    jargs = (tmp / "java_args.txt").read_text().splitlines()
    agent = next(a for a in jargs if a.startswith("-javaagent:"))
    assert agent.endswith(f"={url}/yggdrasil") and "authlib-injector-1.2.8.jar" in agent
    assert any(a.startswith("-Dauthlibinjector.yggdrasil.prefetched=") for a in jargs)
    token = jargs[jargs.index("--accessToken") + 1]
    assert len(token) == 64 and jargs[jargs.index("--userType") + 1] == "mojang"
    pmc = (tmp / "pmc_args.txt").read_text().splitlines()
    assert pmc[pmc.index("--username") + 1] == "Diver"

    # the token the game received can join a server as Diver (what authlib-injector's session call does)
    import urllib.request
    req = urllib.request.Request(f"{url}/yggdrasil/sessionserver/session/minecraft/join", method="POST",
                                 data=json.dumps({"accessToken": token, "selectedProfile": pmc[pmc.index("--uuid") + 1],
                                                  "serverId": "abc"}).encode(),
                                 headers={"Content-Type": "application/json"})
    assert urllib.request.urlopen(req).status == 204
    with urllib.request.urlopen(f"{url}/yggdrasil/sessionserver/session/minecraft/hasJoined?username=Diver&serverId=abc") as r:
        prof = json.loads(r.read())
    assert prof["name"] == "Diver" and prof["properties"][0]["signature"]
    # no secret ever reached the logs
    text = "".join(p.read_text(errors="replace") for p in paths.log_dir.glob("*.log"))
    assert token not in text and "deep blue kelp" not in text

    state.logout()
    assert not state.signed_in and not paths.session_file.exists()
    win.close()
    state.runner.shutdown()
