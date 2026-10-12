"""Line icons (24x24, drawn for Luminary) rendered from inline SVG, cached per color/size."""
from __future__ import annotations

from functools import lru_cache

from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QGuiApplication, QIcon, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer

from . import theme

_PATHS = {
    "home": '<path d="M3 11 12 4l9 7"/><path d="M5.5 9.5V20h5v-6h3v6h5V9.5"/>',
    "server": '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/>'
              '<path d="M7 7.5h.01M7 16.5h.01"/>',
    "settings": '<path d="M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1"/><circle cx="15" cy="6" r="2"/>'
                '<circle cx="9" cy="12" r="2"/><circle cx="17" cy="18" r="2"/>',
    "mods": '<path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z"/><path d="M4 7.5 12 12l8-4.5M12 12v9"/>',
    "friends": '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6"/>'
               '<circle cx="17" cy="9" r="2.5"/><path d="M16.5 14.2c2.8.3 5 2.4 5 5.3"/>',
    "profile": '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"/>',
    "exit": '<path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4"/><path d="M10 16l-4-4 4-4M6 12h10"/>',
    "play": '<path d="M7 4.5v15l12-7.5z" fill="currentColor"/>',
    "refresh": '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
    "folder": '<path d="M3 6.5a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    "close": '<path d="M6 6l12 12M18 6 6 18"/>',
    "minimize": '<path d="M5 12h14"/>',
    "maximize": '<rect x="5" y="5" width="14" height="14" rx="1.5"/>',
    "restore": '<rect x="4" y="8" width="12" height="12" rx="1.5"/><path d="M8 8V5.5A1.5 1.5 0 0 1 9.5 4H18.5A1.5 1.5 0 0 1 20 5.5v9a1.5 1.5 0 0 1-1.5 1.5H16"/>',
    "copy": '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    "upload": '<path d="M12 15V4M7 9l5-5 5 5"/><path d="M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',
    "trash": '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
    "search": '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    "check": '<path d="m5 12 5 5 9-10"/>',
    "user_plus": '<circle cx="10" cy="8" r="4"/><path d="M3 21c0-4 3.1-7 7-7 1.6 0 3 .4 4.2 1.2"/><path d="M19 14v6M16 17h6"/>',
    "alert": '<path d="M12 3.5 2.5 20h19z"/><path d="M12 10v4.5M12 17.2h.01"/>',
    "eye": '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    "eye_off": '<path d="M3 3l18 18"/><path d="M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6C3.7 8.5 2 12 2 12s3.6 7 10 7a9.6 9.6 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
    "log": '<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h7M9 17h7"/>',
    "memory": '<rect x="5" y="7" width="14" height="10" rx="1.5"/><path d="M8.5 7V4M12 7V4M15.5 7V4M8.5 17v3M12 17v3M15.5 17v3"/>',
    "box": '<path d="M3 7.5 12 3l9 4.5-9 4.5z"/><path d="M3 7.5v9L12 21l9-4.5v-9M12 12v9"/>',
    "skin": '<rect x="8" y="2.5" width="8" height="7" rx="1"/><path d="M7 11h10v6H7zM5 11h2v6H5zM17 11h2v6h-2zM8.5 17h3v4.5h-3zM12.5 17h3v4.5h-3z"/>',
    "dot": '<circle cx="12" cy="12" r="5" fill="currentColor" stroke="none"/>',
    "wifi_off": '<path d="M3 3l18 18"/><path d="M8.5 16.5a5 5 0 0 1 7 0M5 12.8a10 10 0 0 1 5.5-2.7M13.5 10.1A10 10 0 0 1 19 12.8M2 9a15 15 0 0 1 4.3-2.6M10.5 5.1A15 15 0 0 1 22 9"/><path d="M12 20h.01"/>',
}


def _svg(name: str, color: str, stroke: float) -> bytes:
    body = _PATHS[name].replace("currentColor", color)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{color}" '
            f'stroke-width="{stroke}" stroke-linecap="round" stroke-linejoin="round">{body}</svg>').encode()


@lru_cache(maxsize=256)
def pixmap(name: str, size: int = 18, color: str = theme.TEXT, stroke: float = 1.8) -> QPixmap:
    app = QGuiApplication.instance()
    dpr = app.devicePixelRatio() if app else 1.0
    pm = QPixmap(int(size * dpr), int(size * dpr))
    pm.fill(Qt.transparent)
    renderer = QSvgRenderer(QByteArray(_svg(name, color, stroke)))
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    renderer.render(p, QRectF(0, 0, size * dpr, size * dpr))
    p.end()
    pm.setDevicePixelRatio(dpr)
    return pm


def icon(name: str, color: str = theme.TEXT, active_color: str | None = None, size: int = 18) -> QIcon:
    ic = QIcon()
    ic.addPixmap(pixmap(name, size, color), QIcon.Normal, QIcon.Off)
    ic.addPixmap(pixmap(name, size, active_color or color), QIcon.Normal, QIcon.On)
    ic.addPixmap(pixmap(name, size, theme.SUBTLE), QIcon.Disabled, QIcon.Off)
    return ic


def names() -> list[str]:
    return sorted(_PATHS)
