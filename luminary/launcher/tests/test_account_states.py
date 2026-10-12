"""Account-state transitions against the real backend: expired/revoked sessions, outages and recovery."""
import json
import socket
import sqlite3
import sys
import threading
import time

import pytest

from conftest import ROOT
from test_ui import app, install, make_window, pack_server, wait_until  # noqa: F401  (fixtures)
from luminary_app import profiles, secure_store
from luminary_app.api import LuminaryApi

BACKEND = ROOT.parent / "backend"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Backend:
    def __init__(self, tmp_path, port):
        pytest.importorskip("fastapi")
        self.uvicorn = pytest.importorskip("uvicorn")
        sys.path.insert(0, str(BACKEND))
        from luminary_auth import config
        self.port = port
        self.url = f"http://127.0.0.1:{port}"
        self.settings = config.Settings(data_dir=tmp_path / "bdata", public_url=self.url)
        self.server = None

    def start(self):
        from luminary_auth.app import create_app
        self.server = self.uvicorn.Server(self.uvicorn.Config(create_app(self.settings), host="127.0.0.1",
                                                              port=self.port, log_level="warning"))
        threading.Thread(target=self.server.run, daemon=True).start()
        assert wait_until(lambda: self.server.started, 10)
        return self

    def stop(self):
        if self.server:
            self.server.should_exit = True
            wait_until(lambda: not self.server.started or self.server.should_exit, 5)

    def db(self):
        return sqlite3.connect(self.settings.db_path)


@pytest.fixture
def backend(tmp_path):
    b = Backend(tmp_path, free_port())
    yield b
    b.stop()


def catalog_for(paths, url):
    data = json.loads(paths.profiles_file.read_text())
    data["accounts"]["api_url"] = url
    return profiles.parse_catalog(data)


def store_session(paths, body):
    secure_store.save_session(paths.session_file, {"access_token": body["access_token"],
                                                   "expires_at": body["expires_at"], "username": body["username"]})


def test_expired_session_at_startup_signs_out_with_a_message(app, install, backend):
    paths, java, _ = install
    backend.start()
    body = LuminaryApi(backend.url).register("Diver", "deep blue kelp")
    store_session(paths, body)
    db = backend.db()
    db.execute("UPDATE tokens SET expires_at = ?", (int(time.time()) - 5,))
    db.commit()
    win, state = make_window(paths, java, catalog_for(paths, backend.url))
    toasts = []
    state.toast.connect(lambda text, kind: toasts.append(text))
    state.restore_session()
    assert wait_until(lambda: state.status == "signed_out", 10)
    assert not state.signed_in and not paths.session_file.exists()
    assert any("Сессия истекла" in t for t in toasts)
    assert win.home.player.stack.currentIndex() == 0  # back to nickname/login (accounts optional)
    win.close(); state.runner.shutdown()


def test_session_revoked_elsewhere_is_noticed_while_running(app, install, backend):
    """'Sign out everywhere' or a password change on another PC must end this launcher's session too."""
    paths, java, _ = install
    backend.start()
    body = LuminaryApi(backend.url).register("Diver", "deep blue kelp")
    store_session(paths, body)
    win, state = make_window(paths, java, catalog_for(paths, backend.url))
    state.restore_session()
    assert wait_until(lambda: state.signed_in, 10)
    other = LuminaryApi(backend.url)
    other.token = other.login("Diver", "deep blue kelp")["access_token"]
    other.logout_all()                       # revokes every session, including this launcher's
    state.send_presence()                    # what the 60 s heartbeat does
    assert wait_until(lambda: state.status == "signed_out", 10)
    assert not paths.session_file.exists()
    assert win.titlebar.account.text() == "Войти"
    win.close(); state.runner.shutdown()


def test_backend_outage_at_startup_then_recovery(app, install, tmp_path):
    paths, java, _ = install
    port = free_port()
    b = Backend(tmp_path, port).start()
    body = LuminaryApi(b.url).register("Diver", "deep blue kelp")
    store_session(paths, body)
    b.stop()
    assert wait_until(lambda: _refused(port), 10)
    win, state = make_window(paths, java, catalog_for(paths, b.url))
    state.restore_session()
    assert wait_until(lambda: state.status == "offline", 15)
    assert paths.session_file.exists()                 # an outage is not a sign-out
    assert win.home.player.stack.currentIndex() == 0   # nickname play stays possible
    assert win.titlebar.account.text() == "Diver · нет связи"        # not "Войти": the user is signed in
    assert win.home.player.login_link.isHidden()
    assert state.retry_timer.isActive()                # and the launcher keeps trying
    b2 = Backend(tmp_path, port).start()               # the service comes back
    statuses = []
    state.account_status.connect(statuses.append)
    state.retry_offline()                              # what the retry timer does
    assert wait_until(lambda: state.signed_in, 15)
    assert state.status == "ok" and "checking" not in statuses  # silent: no UI flicker while retrying
    assert not state.retry_timer.isActive()
    assert win.home.player.stack.currentIndex() == 2
    assert win.titlebar.account.text() == "Diver"
    b2.stop(); win.close(); state.runner.shutdown()


def test_network_failure_while_signed_in_switches_to_offline(app, install, tmp_path):
    paths, java, _ = install
    port = free_port()
    b = Backend(tmp_path, port).start()
    store_session(paths, LuminaryApi(b.url).register("Diver", "deep blue kelp"))
    win, state = make_window(paths, java, catalog_for(paths, b.url))
    state.restore_session()
    assert wait_until(lambda: state.signed_in, 10)
    b.stop()
    assert wait_until(lambda: _refused(port), 10)
    state.send_presence()
    assert wait_until(lambda: state.status == "offline", 15)
    assert paths.session_file.exists() and state.retry_timer.isActive()
    win.close(); state.runner.shutdown()


def test_session_close_to_expiry_is_rotated_while_running(app, install, backend):
    paths, java, _ = install
    backend.start()
    body = LuminaryApi(backend.url).register("Diver", "deep blue kelp")
    store_session(paths, body)
    win, state = make_window(paths, java, catalog_for(paths, backend.url))
    state.restore_session()
    assert wait_until(lambda: state.signed_in, 10)
    old = state.session["access_token"]
    state.session["expires_at"] = int(time.time()) + 3600   # launcher left open for weeks
    state.send_presence()
    assert wait_until(lambda: state.session and state.session["access_token"] != old, 10)
    assert secure_store.load_session(paths.session_file)["access_token"] == state.session["access_token"]
    assert state.signed_in
    win.close(); state.runner.shutdown()


def _refused(port):
    try:
        socket.create_connection(("127.0.0.1", port), timeout=0.5).close()
        return False
    except OSError:
        return True
