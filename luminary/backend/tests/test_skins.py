import io
import struct
import zlib

from conftest import auth, make_skin, register
from PIL import Image, PngImagePlugin

from luminary_auth.skins import SkinError, detect_model, validate_skin

import pytest

LIMIT = 128 * 1024


def test_valid_modern_and_legacy_skins():
    s = validate_skin(make_skin(64, 64), LIMIT)
    assert (s.width, s.height) == (64, 64) and len(s.sha256) == 64
    s = validate_skin(make_skin(64, 32), LIMIT)
    assert (s.width, s.height) == (64, 32) and s.suggested_model == "classic"


def test_slim_detection():
    assert validate_skin(make_skin(slim=True), LIMIT).suggested_model == "slim"
    assert validate_skin(make_skin(slim=False), LIMIT).suggested_model == "classic"


@pytest.mark.parametrize("size", [(32, 32), (128, 128), (64, 48), (65, 64)])
def test_wrong_dimensions_rejected(size):
    with pytest.raises(SkinError):
        validate_skin(make_skin(*size), LIMIT)


def test_extension_and_mime_are_not_trusted():
    gif = io.BytesIO()
    Image.new("RGBA", (64, 64), (255, 0, 0, 255)).save(gif, format="GIF")
    with pytest.raises(SkinError):
        validate_skin(gif.getvalue(), LIMIT)
    with pytest.raises(SkinError):
        validate_skin(b"<?php echo 1; ?>" + b"\0" * 100, LIMIT)


def test_corrupt_crc_and_truncation_rejected():
    data = bytearray(make_skin())
    data[29] ^= 0xFF  # IHDR CRC
    with pytest.raises(SkinError):
        validate_skin(bytes(data), LIMIT)
    good = make_skin()
    with pytest.raises(SkinError):
        validate_skin(good[: len(good) // 2], LIMIT)


def test_size_limit_and_empty():
    with pytest.raises(SkinError):
        validate_skin(b"", LIMIT)
    with pytest.raises(SkinError):
        validate_skin(make_skin() + b"\0" * LIMIT, LIMIT)


def test_ihdr_lies_about_dimensions_huge_image_rejected_before_decode():
    # A 20000x20000 PNG header: rejected from the header alone, nothing is decoded.
    ihdr = struct.pack(">IIBBBBB", 20000, 20000, 8, 6, 0, 0, 0)
    chunk = struct.pack(">I", 13) + b"IHDR" + ihdr + struct.pack(">I", zlib.crc32(b"IHDR" + ihdr) & 0xFFFFFFFF)
    with pytest.raises(SkinError):
        validate_skin(b"\x89PNG\r\n\x1a\n" + chunk + b"\0" * 64, LIMIT)


def test_fully_transparent_rejected():
    with pytest.raises(SkinError):
        validate_skin(make_skin(color=(0, 0, 0, 0)), LIMIT)


def test_metadata_is_stripped():
    img = Image.open(io.BytesIO(make_skin()))
    info = PngImagePlugin.PngInfo()
    info.add_text("Comment", "secret-ish metadata")
    buf = io.BytesIO()
    img.save(buf, format="PNG", pnginfo=info)
    out = validate_skin(buf.getvalue(), LIMIT).png
    assert b"secret-ish" not in out


def test_upload_serve_replace_delete(client, settings):
    token = register(client)["access_token"]
    r = client.put("/api/v1/me/skin?model=auto", content=make_skin(slim=True), headers=auth(token))
    assert r.status_code == 200, r.text
    skin = r.json()["profile"]["skin"]
    assert skin["model"] == "slim" and skin["url"].endswith(skin["hash"])
    t = client.get(f"/textures/{skin['hash']}")
    assert t.status_code == 200 and t.headers["content-type"] == "image/png"
    assert "immutable" in t.headers["cache-control"]
    first = skin["hash"]

    r = client.put("/api/v1/me/skin?model=classic", content=make_skin(color=(10, 200, 10, 255)), headers=auth(token))
    second = r.json()["profile"]["skin"]["hash"]
    assert second != first
    assert client.get(f"/textures/{first}").status_code == 404  # unreferenced texture removed
    assert r.json()["profile"]["skin"]["model"] == "classic"

    r = client.patch("/api/v1/me/skin", json={"model": "slim"}, headers=auth(token))
    assert r.json()["profile"]["skin"]["model"] == "slim"

    r = client.delete("/api/v1/me/skin", headers=auth(token))
    assert r.json()["profile"]["skin"]["url"] is None
    assert client.get(f"/textures/{second}").status_code == 404


def test_upload_requires_auth_and_valid_png(client):
    assert client.put("/api/v1/me/skin", content=make_skin()).status_code == 401
    token = register(client)["access_token"]
    r = client.put("/api/v1/me/skin", content=b"not a png at all", headers=auth(token))
    assert r.status_code == 400 and r.json()["error"] == "invalid_skin"
    r = client.put("/api/v1/me/skin", content=b"\0" * (200 * 1024), headers=auth(token))
    assert r.status_code == 400


def test_shared_texture_kept_while_referenced(client):
    a = register(client, "Steve")["access_token"]
    b = register(client, "Alex", "another good pw")["access_token"]
    png = make_skin()
    h = client.put("/api/v1/me/skin", content=png, headers=auth(a)).json()["profile"]["skin"]["hash"]
    client.put("/api/v1/me/skin", content=png, headers=auth(b))
    client.delete("/api/v1/me/skin", headers=auth(a))
    assert client.get(f"/textures/{h}").status_code == 200


def test_texture_path_traversal(client):
    assert client.get("/textures/..%2F..%2Fluminary.sqlite3").status_code == 404
    assert client.get("/textures/" + "g" * 64).status_code == 404


def test_detect_model_on_legacy():
    img = Image.new("RGBA", (64, 32), (1, 2, 3, 255))
    assert detect_model(img) == "classic"
