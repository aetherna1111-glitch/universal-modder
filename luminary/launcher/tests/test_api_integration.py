"""Launcher API client against the real backend running under uvicorn on localhost."""
import io
import shutil
import socket
import sys
import threading
import time
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parents[2] / "backend"
sys.path.insert(0, str(BACKEND))
pytest.importorskip("fastapi")
uvicorn = pytest.importorskip("uvicorn")

from luminary_app.api import ApiError, LuminaryApi  # noqa: E402


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@pytest.fixture(scope="module")
def backend(tmp_path_factory):
    from luminary_auth import config
    from luminary_auth.app import create_app

    port = _free_port()
    data = tmp_path_factory.mktemp("backend-data")
    settings = config.Settings(data_dir=data, public_url=f"http://127.0.0.1:{port}")
    app = create_app(settings)
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}", app
    server.should_exit = True
    t.join(5)


def skin_png(color=(30, 120, 200, 255)):
    from PIL import Image
    img = Image.new("RGBA", (64, 64), color)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def test_account_skin_and_game_token_flow(backend):
    url, app = backend
    api = LuminaryApi(url)
    with pytest.raises(ApiError) as ei:
        api.register("ab", "short")
    assert ei.value.status == 400 and "Ник" in ei.value.message

    reg = api.register("Diver", "deep blue kelp")
    api.token = reg["access_token"]
    assert api.me()["profile"]["name"] == "Diver"

    me = api.upload_skin(skin_png(), "classic")
    skin_url = me["profile"]["skin"]["url"]
    png = api.fetch_texture(skin_url)
    assert png.startswith(b"\x89PNG")
    assert api.set_skin_model("slim")["profile"]["skin"]["model"] == "slim"

    game = api.game_token()
    meta = api.yggdrasil_metadata_raw(game["yggdrasil_url"])
    assert b"signaturePublickey" in meta

    with pytest.raises(ApiError) as ei:
        api.upload_skin(b"GIF89a....", "classic")
    assert ei.value.status == 400

    api.logout()
    with pytest.raises(ApiError) as ei:
        api.me()
    assert ei.value.unauthorized


def test_wrong_password_and_unknown_user_look_the_same(backend):
    url, _ = backend
    api = LuminaryApi(url)
    api.register("Coral", "pink and orange")
    errs = []
    for name in ("Coral", "NobodyHere"):
        with pytest.raises(ApiError) as ei:
            api.login(name, "wrong password")
        errs.append((ei.value.status, ei.value.message))
    assert errs[0] == errs[1]


def test_texture_from_foreign_host_refused(backend):
    url, _ = backend
    with pytest.raises(ApiError):
        LuminaryApi(url).fetch_texture("https://evil.example.org/textures/abc")


def test_unreachable_server_is_reported_not_raised_raw():
    api = LuminaryApi(f"http://127.0.0.1:{_free_port()}", timeout=2)
    with pytest.raises(ApiError) as ei:
        api.login("x", "y")
    assert ei.value.offline


def test_not_configured():
    with pytest.raises(ApiError) as ei:
        LuminaryApi("").me()
    assert ei.value.code == "not_configured"
