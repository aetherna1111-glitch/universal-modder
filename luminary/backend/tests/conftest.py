import io
import shutil
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from luminary_auth import config  # noqa: E402
from luminary_auth.app import create_app  # noqa: E402
from luminary_auth.signing import Signer  # noqa: E402


@pytest.fixture(scope="session")
def key_file(tmp_path_factory):
    path = tmp_path_factory.mktemp("key") / "signing_key.pem"
    Signer.load_or_create(path)  # 4096-bit generation once per test session
    return path


@pytest.fixture
def settings(tmp_path, key_file):
    data = tmp_path / "data"
    data.mkdir()
    shutil.copy(key_file, data / "signing_key.pem")
    return config.Settings(data_dir=data, public_url="http://localhost:8080")


@pytest.fixture
def app(settings):
    return create_app(settings)


@pytest.fixture
def client(app):
    return TestClient(app, client=("203.0.113.5", 50000))


def make_skin(width=64, height=64, slim=False, color=(200, 30, 30, 255)) -> bytes:
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    for x in range(width):
        for y in range(height):
            img.putpixel((x, y), color)
    if slim and height == 64:
        for x in (54, 55):
            for y in range(20, 32):
                img.putpixel((x, y), (0, 0, 0, 0))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def register(client, name="Steve", password="correct horse battery"):
    r = client.post("/api/v1/auth/register", json={"username": name, "password": password})
    assert r.status_code == 201, r.text
    return r.json()


def auth(token):
    return {"Authorization": f"Bearer {token}"}
