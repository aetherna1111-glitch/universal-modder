"""Interactive 3D preview of a Minecraft skin, drawn with QPainter (no OpenGL/WebEngine dependency).

Each visible box face is a textured parallelogram: under an orthographic projection a rectangle maps to
a parallelogram, so an affine QTransform maps the texture region exactly. Faces are back-face culled and
depth sorted. UV layout follows the standard 64x64 skin format (classic 4px and slim 3px arms, second
layer); legacy 64x32 skins mirror the right limbs, as Minecraft does.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

from PySide6.QtCore import QPointF, QRectF, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QImage, QPainter, QTransform
from PySide6.QtWidgets import QSizePolicy, QWidget

MAX_SKIN_BYTES = 128 * 1024


class SkinFileError(ValueError):
    pass


@dataclass(frozen=True)
class Box:
    size: tuple[int, int, int]        # w, h, d in texels
    center: tuple[float, float, float]
    uv: tuple[int, int]
    inflate: float = 0.0
    overlay: bool = False
    mirror: bool = False


def model_boxes(slim: bool, legacy: bool) -> list[Box]:
    aw = 3 if slim else 4
    ax = 4 + aw / 2
    boxes = [
        Box((8, 8, 8), (0, 28, 0), (0, 0)),
        Box((8, 8, 8), (0, 28, 0), (32, 0), 0.5, True),
        Box((8, 12, 4), (0, 18, 0), (16, 16)),
        Box((aw, 12, 4), (-ax, 18, 0), (40, 16)),          # right arm (viewer's left)
        Box((4, 12, 4), (-2, 6, 0), (0, 16)),              # right leg
    ]
    if legacy:
        boxes += [Box((aw, 12, 4), (ax, 18, 0), (40, 16), mirror=True),
                  Box((4, 12, 4), (2, 6, 0), (0, 16), mirror=True)]
    else:
        boxes += [
            Box((aw, 12, 4), (ax, 18, 0), (32, 48)),        # left arm
            Box((4, 12, 4), (2, 6, 0), (16, 48)),           # left leg
            Box((8, 12, 4), (0, 18, 0), (16, 32), 0.25, True),
            Box((aw, 12, 4), (-ax, 18, 0), (40, 32), 0.25, True),
            Box((aw, 12, 4), (ax, 18, 0), (48, 48), 0.25, True),
            Box((4, 12, 4), (-2, 6, 0), (0, 32), 0.25, True),
            Box((4, 12, 4), (2, 6, 0), (0, 48), 0.25, True),
        ]
    return boxes


def box_faces(b: Box):
    """Yield (normal, (TL, TR, BL), (u, v, w, h), flip) for the six faces of a box."""
    w, h, d = b.size
    u, v = b.uv
    hx, hy, hz = w / 2 + b.inflate, h / 2 + b.inflate, d / 2 + b.inflate
    cx, cy, cz = b.center

    def P(x, y, z):
        return (cx + x, cy + y, cz + z)

    right_rect = (u, v + d, d, h)
    left_rect = (u + d + w, v + d, d, h)
    if b.mirror:
        right_rect, left_rect = left_rect, right_rect
    faces = [
        ((0, 0, 1), (P(-hx, hy, hz), P(hx, hy, hz), P(-hx, -hy, hz)), (u + d, v + d, w, h)),
        ((0, 0, -1), (P(hx, hy, -hz), P(-hx, hy, -hz), P(hx, -hy, -hz)), (u + 2 * d + w, v + d, w, h)),
        ((-1, 0, 0), (P(-hx, hy, -hz), P(-hx, hy, hz), P(-hx, -hy, -hz)), right_rect),
        ((1, 0, 0), (P(hx, hy, hz), P(hx, hy, -hz), P(hx, -hy, hz)), left_rect),
        ((0, 1, 0), (P(-hx, hy, -hz), P(hx, hy, -hz), P(-hx, hy, hz)), (u + d, v, w, d)),
        ((0, -1, 0), (P(-hx, -hy, -hz), P(hx, -hy, -hz), P(-hx, -hy, hz)), (u + d + w, v, w, d)),
    ]
    for normal, corners, rect in faces:
        yield normal, corners, rect, b.mirror


def _rotate(p, cy_, sy_, cp, sp):
    x, y, z = p
    # yaw around Y, then pitch around X
    x, z = x * cy_ + z * sy_, -x * sy_ + z * cy_
    y, z = y * cp - z * sp, y * sp + z * cp
    return x, y, z


class SkinRenderer:
    def __init__(self, texture: QImage, slim: bool = False):
        self.set_texture(texture, slim)

    def set_texture(self, texture: QImage, slim: bool | None = None) -> None:
        tex = texture.convertToFormat(QImage.Format_ARGB32)
        self.legacy = tex.height() == 32
        self.overlay_tex = tex
        base = tex.copy()
        # Minecraft draws the first layer opaque; transparent base pixels would look like holes.
        base_opaque = base.convertToFormat(QImage.Format_RGB32).convertToFormat(QImage.Format_ARGB32)
        self.base_tex = base_opaque
        if slim is not None:
            self.slim = slim
        self._boxes = model_boxes(self.slim, self.legacy)

    def set_slim(self, slim: bool) -> None:
        self.slim = slim
        self._boxes = model_boxes(slim, self.legacy)

    def paint(self, p: QPainter, rect: QRectF, yaw_deg: float, pitch_deg: float, zoom: float = 1.0) -> None:
        cy_, sy_ = math.cos(math.radians(yaw_deg)), math.sin(math.radians(yaw_deg))
        cp, sp = math.cos(math.radians(pitch_deg)), math.sin(math.radians(pitch_deg))
        scale = min(rect.width() / 22.0, rect.height() / 38.0) * zoom
        ox, oy = rect.center().x(), rect.center().y()
        center_y = 16.0  # model is 32 texels tall (+hat); rotate around its middle
        faces = []
        for b in self._boxes:
            for normal, corners, (u, v, w, h), flip in box_faces(b):
                n = _rotate(normal, cy_, sy_, cp, sp)
                if n[2] <= 1e-6:
                    continue
                pts = []
                for c in corners:
                    x, y, z = _rotate((c[0], c[1] - center_y, c[2]), cy_, sy_, cp, sp)
                    pts.append((ox + x * scale, oy - y * scale, z))
                tl, tr, bl = pts
                depth = (tr[2] + bl[2]) / 2 + (0.01 if b.overlay else 0.0)
                faces.append((depth, tl, tr, bl, (u, v, w, h), flip, b.overlay, n))
        faces.sort(key=lambda f: f[0])
        p.save()
        p.setRenderHint(QPainter.SmoothPixmapTransform, False)
        p.setRenderHint(QPainter.Antialiasing, False)
        for _depth, tl, tr, bl, (u, v, w, h), flip, overlay, n in faces:
            if flip:
                br = (tr[0] + bl[0] - tl[0], tr[1] + bl[1] - tl[1])
                tl, tr, bl = tr, tl, br
            t = QTransform((tr[0] - tl[0]) / w, (tr[1] - tl[1]) / w,
                           (bl[0] - tl[0]) / h, (bl[1] - tl[1]) / h, tl[0], tl[1])
            p.setTransform(t)
            e = 0.03  # hide hairline seams between neighbouring faces
            src = self.overlay_tex if overlay else self.base_tex
            p.drawImage(QRectF(-e, -e, w + 2 * e, h + 2 * e), src, QRectF(u, v, w, h))
            # gentle directional shading for depth cues
            shade = int(max(0.0, 0.32 - 0.32 * (0.55 * n[2] + 0.45 * max(n[1], 0) + 0.2)) * 255)
            if shade > 0 and not overlay:
                p.fillRect(QRectF(0, 0, w, h), QColor(4, 16, 20, shade))
        p.restore()


def load_skin_bytes(data: bytes) -> tuple[QImage, str]:
    """Local pre-check mirroring the server rules. Returns (image, suggested model)."""
    if not data:
        raise SkinFileError("Файл пустой")
    if len(data) > MAX_SKIN_BYTES:
        raise SkinFileError(f"Файл больше {MAX_SKIN_BYTES // 1024} КБ")
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SkinFileError("Это не PNG-файл")
    img = QImage()
    if not img.loadFromData(data, "PNG"):
        raise SkinFileError("Не удалось прочитать PNG: файл повреждён")
    if (img.width(), img.height()) not in ((64, 64), (64, 32)):
        raise SkinFileError(f"Скин должен быть 64x64 или 64x32, а не {img.width()}x{img.height()}")
    img = img.convertToFormat(QImage.Format_ARGB32)
    return img, detect_model(img)


def detect_model(img: QImage) -> str:
    if img.height() != 64:
        return "classic"
    for x in (54, 55):
        for y in range(20, 32):
            if QColor.fromRgba(img.pixel(x, y)).alpha() != 0:
                return "classic"
    return "slim"


def placeholder_skin() -> QImage:
    """A plain Luminary diver used when no skin is set (no Mojang assets are shipped)."""
    img = QImage(64, 64, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)

    def fill(x, y, w, h, color):
        p.fillRect(x, y, w, h, QColor(color))

    skin, hair, shirt, shirt2, pants, shoes = "#c8a07a", "#2b1d14", "#1f8f86", "#3dd6c4", "#1b3440", "#10222a"
    fill(0, 0, 32, 16, skin)                     # head
    fill(8, 0, 8, 8, hair); fill(0, 8, 32, 2, hair); fill(24, 8, 8, 8, hair)
    fill(9, 12, 2, 1, "#ffffff"); fill(13, 12, 2, 1, "#ffffff"); fill(10, 12, 1, 1, "#1b4f72"); fill(14, 12, 1, 1, "#1b4f72")
    fill(16, 16, 24, 16, shirt); fill(20, 20, 8, 3, shirt2)          # body
    for (x, y) in ((40, 16), (32, 48)):                               # arms
        fill(x, y, 16, 16, shirt); fill(x, y + 12, 16, 4, skin)
    for (x, y) in ((0, 16), (16, 48)):                                # legs
        fill(x, y, 16, 16, pants); fill(x, y + 13, 16, 3, shoes)
    p.end()
    return img


def head_pixmap(texture: QImage, size: int):
    """Face (front of head + hat layer), nearest-neighbour scaled, for avatars."""
    from PySide6.QtGui import QPixmap
    img = QImage(8, 8, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)
    p.drawImage(QRectF(0, 0, 8, 8), texture.convertToFormat(QImage.Format_RGB32), QRectF(8, 8, 8, 8))
    p.drawImage(QRectF(0, 0, 8, 8), texture, QRectF(40, 8, 8, 8))
    p.end()
    return QPixmap.fromImage(img.scaled(size, size, Qt.IgnoreAspectRatio, Qt.FastTransformation))


class SkinView(QWidget):
    """Drag to rotate, wheel or +/- to zoom, arrow keys to rotate, R to reset."""

    rotated = Signal()

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.renderer = SkinRenderer(placeholder_skin(), False)
        self.yaw, self.pitch, self.zoom = -28.0, 8.0, 1.0
        self._drag: QPointF | None = None
        self._auto = True
        self.reduce_motion = False
        self.timer = QTimer(self)
        self.timer.setInterval(33)
        self.timer.timeout.connect(self._tick)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setMinimumSize(220, 320)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setCursor(Qt.OpenHandCursor)
        self.setAccessibleName("3D-просмотр скина")
        self.setToolTip("Перетащи, чтобы повернуть. Колесо мыши - масштаб. R - сбросить вид.")

    def sizeHint(self) -> QSize:
        return QSize(280, 420)

    def set_skin(self, image: QImage, slim: bool) -> None:
        self.renderer.set_texture(image, slim)
        self.update()

    def set_slim(self, slim: bool) -> None:
        self.renderer.set_slim(slim)
        self.update()

    def reset_view(self) -> None:
        self.yaw, self.pitch, self.zoom = -28.0, 8.0, 1.0
        self.update()

    def set_auto_rotate(self, on: bool) -> None:
        self._auto = on
        self._sync_timer()

    def _sync_timer(self) -> None:
        if self._auto and not self.reduce_motion and self.isVisible() and self._drag is None:
            if not self.timer.isActive():
                self.timer.start()
        else:
            self.timer.stop()

    def showEvent(self, e) -> None:
        super().showEvent(e)
        self._sync_timer()

    def hideEvent(self, e) -> None:
        super().hideEvent(e)
        self.timer.stop()

    def _tick(self) -> None:
        self.yaw = (self.yaw + 0.8) % 360
        self.update()

    def paintEvent(self, _e) -> None:
        p = QPainter(self)
        r = QRectF(self.rect()).adjusted(8, 8, -8, -8)
        # soft floor shadow
        p.setRenderHint(QPainter.Antialiasing, True)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(0, 0, 0, 70))
        w = min(r.width(), r.height() * 0.58) * 0.5 * self.zoom
        p.drawEllipse(QPointF(r.center().x(), r.center().y() + r.height() * 0.43 * self.zoom), w / 2, w / 9)
        self.renderer.paint(p, r, self.yaw, self.pitch, self.zoom)
        if self.hasFocus():
            p.setPen(QColor(61, 214, 196, 120))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(QRectF(self.rect()).adjusted(1, 1, -1, -1), 10, 10)
        p.end()

    def mousePressEvent(self, e) -> None:
        if e.button() == Qt.LeftButton:
            self._drag = e.position()
            self.setCursor(Qt.ClosedHandCursor)
            self._sync_timer()

    def mouseMoveEvent(self, e) -> None:
        if self._drag is not None:
            d = e.position() - self._drag
            self._drag = e.position()
            self.yaw = (self.yaw + d.x() * 0.6) % 360
            self.pitch = max(-60.0, min(60.0, self.pitch + d.y() * 0.4))
            self.update()
            self.rotated.emit()

    def mouseReleaseEvent(self, e) -> None:
        self._drag = None
        self.setCursor(Qt.OpenHandCursor)
        self._auto = False  # the user took control; stop spinning
        self._sync_timer()

    def wheelEvent(self, e) -> None:
        step = 1.1 if e.angleDelta().y() > 0 else 1 / 1.1
        self.zoom = max(0.6, min(2.2, self.zoom * step))
        self.update()

    def keyPressEvent(self, e) -> None:
        k = e.key()
        if k == Qt.Key_Left:
            self.yaw -= 15
        elif k == Qt.Key_Right:
            self.yaw += 15
        elif k == Qt.Key_Up:
            self.pitch = max(-60.0, self.pitch - 10)
        elif k == Qt.Key_Down:
            self.pitch = min(60.0, self.pitch + 10)
        elif k in (Qt.Key_Plus, Qt.Key_Equal):
            self.zoom = min(2.2, self.zoom * 1.1)
        elif k == Qt.Key_Minus:
            self.zoom = max(0.6, self.zoom / 1.1)
        elif k == Qt.Key_R:
            self.reset_view()
        else:
            super().keyPressEvent(e)
            return
        self._auto = False
        self._sync_timer()
        self.update()
