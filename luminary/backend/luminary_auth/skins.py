"""Skin PNG validation, canonicalisation and content-addressed storage.

The upload is checked structurally *before* any decoding: PNG signature, an IHDR chunk with a valid CRC,
and dimensions of exactly 64x64 (modern) or 64x32 (legacy). Only then is it decoded with Pillow (whose
work is bounded by those dimensions and the byte limit), converted to RGBA and re-encoded without any
metadata. The stored file is named by the SHA-256 of the canonical PNG, which Minecraft also uses as the
texture cache key (the last path segment of the URL).
"""
from __future__ import annotations

import hashlib
import io
import os
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ALLOWED_SIZES = {(64, 64), (64, 32)}
VALID_COLOR_TYPES = {0: {1, 2, 4, 8, 16}, 2: {8, 16}, 3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
HASH_LEN = 64


class SkinError(ValueError):
    """User-facing reason a file is not an acceptable skin."""


@dataclass(frozen=True)
class Skin:
    png: bytes
    width: int
    height: int
    sha256: str
    suggested_model: str  # 'classic' or 'slim'


def _parse_ihdr(data: bytes) -> tuple[int, int]:
    if len(data) < 8 + 25:
        raise SkinError("Файл слишком короткий для PNG")
    if data[:8] != PNG_SIGNATURE:
        raise SkinError("Это не PNG-файл")
    length, ctype = struct.unpack(">I4s", data[8:16])
    if ctype != b"IHDR" or length != 13:
        raise SkinError("Повреждённый PNG: нет заголовка IHDR")
    body = data[16:29]
    (crc,) = struct.unpack(">I", data[29:33])
    if zlib.crc32(b"IHDR" + body) & 0xFFFFFFFF != crc:
        raise SkinError("Повреждённый PNG: неверная контрольная сумма")
    width, height, depth, color, compression, flt, interlace = struct.unpack(">IIBBBBB", body)
    if color not in VALID_COLOR_TYPES or depth not in VALID_COLOR_TYPES[color]:
        raise SkinError("Неподдерживаемый формат цвета PNG")
    if compression != 0 or flt != 0 or interlace not in (0, 1):
        raise SkinError("Неподдерживаемые параметры PNG")
    if (width, height) not in ALLOWED_SIZES:
        raise SkinError(f"Скин должен быть 64x64 или 64x32 пикселя, а не {width}x{height}")
    return width, height


def detect_model(img: Image.Image) -> str:
    """Slim (Alex) skins leave the outer column of the right arm transparent (x 54-55, y 20-31)."""
    if img.height != 64:
        return "classic"
    alpha = img.getchannel("A")
    for x in (54, 55):
        for y in range(20, 32):
            if alpha.getpixel((x, y)) != 0:
                return "classic"
    return "slim"


def validate_skin(data: bytes, max_bytes: int) -> Skin:
    if not data:
        raise SkinError("Пустой файл")
    if len(data) > max_bytes:
        raise SkinError(f"Файл больше {max_bytes // 1024} КБ")
    width, height = _parse_ihdr(data)
    try:
        with Image.open(io.BytesIO(data), formats=["PNG"]) as im:
            if im.size != (width, height):
                raise SkinError("Повреждённый PNG")
            im.load()
            rgba = im.convert("RGBA")
    except SkinError:
        raise
    except Exception as exc:  # truncated data, bad zlib stream, bad palette...
        raise SkinError("Не удалось прочитать PNG: файл повреждён") from exc
    if rgba.getchannel("A").getextrema() == (0, 0):
        raise SkinError("Скин полностью прозрачный")
    out = io.BytesIO()
    rgba.save(out, format="PNG", optimize=True)  # no text/ICC/exif chunks carried over
    png = out.getvalue()
    return Skin(png=png, width=width, height=height, sha256=hashlib.sha256(png).hexdigest(),
                suggested_model=detect_model(rgba))


def is_valid_hash(value: str) -> bool:
    return len(value) == HASH_LEN and all(c in "0123456789abcdef" for c in value)


class TextureStore:
    def __init__(self, root: Path):
        self.root = root

    def path_for(self, sha256: str) -> Path:
        if not is_valid_hash(sha256):
            raise ValueError("invalid texture hash")
        return self.root / sha256[:2] / f"{sha256}.png"

    def save(self, skin: Skin) -> Path:
        path = self.path_for(skin.sha256)
        if path.exists():
            return path
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        with open(tmp, "wb") as f:
            f.write(skin.png)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        return path

    def delete(self, sha256: str) -> None:
        try:
            self.path_for(sha256).unlink()
        except FileNotFoundError:
            pass
