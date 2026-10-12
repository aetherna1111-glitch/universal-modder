"""Installer artwork from the launcher's own assets (kelp scene, wordmark, icon).

  python make_art.py <output dir>
Writes welcome.bmp (164x314, welcome/finish pages), header.bmp (150x57) and luminary.ico (square,
16-256 px) for the NSIS installer. Needs Pillow.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "assets"
BG = (10, 20, 24)
ACCENT = (61, 214, 196)


def cover(img: Image.Image, w: int, h: int, anchor_x: float = 0.5) -> Image.Image:
    scale = max(w / img.width, h / img.height)
    r = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
    x = int((r.width - w) * anchor_x)
    y = (r.height - h) // 2
    return r.crop((x, y, x + w, y + h))


def vertical_shade(img: Image.Image, top: int, bottom: int) -> Image.Image:
    shade = Image.new("L", (1, img.height))
    for y in range(img.height):
        shade.putpixel((0, y), int(top + (bottom - top) * y / max(img.height - 1, 1)))
    shade = shade.resize(img.size)
    dark = Image.new("RGB", img.size, BG)
    return Image.composite(dark, img, shade)


def wordmark(width: int) -> Image.Image:
    banner = Image.open(ASSETS / "hero_banner.png").convert("RGBA")
    bbox = banner.getchannel("A").getbbox() or (0, 0, banner.width, banner.height)
    banner = banner.crop(bbox)
    h = round(banner.height * width / banner.width)
    return banner.resize((width, h), Image.LANCZOS)


def welcome(out: Path) -> None:
    scene = Image.open(ASSETS / "hero_bg.png").convert("RGB")
    img = vertical_shade(cover(scene, 164, 314, 0.35), 30, 170)
    # glow behind the wordmark so the dark letters stay readable on the water
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-30, 8, 194, 112), fill=(170, 255, 235, 120))
    img = Image.alpha_composite(img.convert("RGBA"), glow.filter(ImageFilter.GaussianBlur(18)))
    mark = wordmark(148)
    img.alpha_composite(mark, (8, 40))
    kelp = Image.open(ASSETS / "left_bg.png").convert("RGBA")
    kelp = cover(kelp, 164, 314, 0.5).convert("RGBA")
    lower = kelp.crop((0, 200, 164, 314))
    mask = lower.convert("L").point(lambda v: 255 if v < 30 else 0)  # the dark kelp silhouettes
    img.paste(Image.new("RGBA", lower.size, (6, 14, 16, 255)), (0, 200), mask)
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 163, 313), outline=ACCENT + (255,))
    img.convert("RGB").save(out / "welcome.bmp")


def header(out: Path) -> None:
    img = Image.new("RGBA", (150, 57), BG + (255,))
    scene = cover(Image.open(ASSETS / "hero_bg.png").convert("RGB"), 150, 57, 0.6)
    img = Image.blend(img, vertical_shade(scene, 120, 200).convert("RGBA"), 0.9)
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((4, 6, 146, 52), fill=(170, 255, 235, 110))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(10)))
    mark = wordmark(136)
    img.alpha_composite(mark, ((150 - mark.width) // 2, (57 - mark.height) // 2))
    img.convert("RGB").save(out / "header.bmp")


def icon(out: Path) -> None:
    src = Image.open(HERE.parent / "luminary.ico").convert("RGBA")
    side = max(src.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(src, ((side - src.width) // 2, (side - src.height) // 2))
    square.save(out / "luminary.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "build"
    out.mkdir(parents=True, exist_ok=True)
    welcome(out)
    header(out)
    icon(out)
    print(f"artwork written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
