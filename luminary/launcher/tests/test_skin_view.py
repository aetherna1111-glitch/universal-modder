import os

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
QtGui = pytest.importorskip("PySide6.QtGui")
from PySide6.QtCore import QRectF, Qt  # noqa: E402
from PySide6.QtGui import QColor, QImage, QPainter  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from luminary_app.ui import skin_view  # noqa: E402

FRONT, BACK, RIGHT, LEFT, TOP, BOTTOM = "#ff0000", "#0000ff", "#00ff00", "#ffff00", "#ffffff", "#ff00ff"


@pytest.fixture(scope="module")
def app():
    return QApplication.instance() or QApplication([])


def head_texture():
    img = QImage(64, 64, QImage.Format_ARGB32)
    img.fill(QColor(0, 0, 0, 0))  # empty second layer
    p = QPainter(img)
    p.fillRect(0, 16, 64, 16, QColor("#404040"))  # body/limbs
    for (x, y, w, h), c in [((8, 8, 8, 8), FRONT), ((24, 8, 8, 8), BACK), ((0, 8, 8, 8), RIGHT),
                            ((16, 8, 8, 8), LEFT), ((8, 0, 8, 8), TOP), ((16, 0, 8, 8), BOTTOM)]:
        p.fillRect(x, y, w, h, QColor(c))
    p.fillRect(8, 8, 1, 1, QColor("#000000"))   # marker: top-left texel of the face
    p.end()
    return img


def render(tex, yaw, pitch, slim=False, size=(220, 380), head_only=False):
    out = QImage(size[0], size[1], QImage.Format_ARGB32)
    out.fill(QColor(0, 0, 0, 0))
    p = QPainter(out)
    r = skin_view.SkinRenderer(tex, slim)
    if head_only:  # from straight below, the body correctly hides the bottom of the head
        r._boxes = r._boxes[:1]
    r.paint(p, QRectF(0, 0, *size), yaw, pitch, 1.0)
    p.end()
    return out


PALETTE = [FRONT, BACK, RIGHT, LEFT, TOP, BOTTOM, "#000000", "#404040"]


def color_at(img, x, y):
    """Nearest palette colour (faces are lightly shaded, so exact values differ)."""
    c = QColor(img.pixel(int(x), int(y)))
    def dist(h):
        q = QColor(h)
        return (q.red() - c.red()) ** 2 + (q.green() - c.green()) ** 2 + (q.blue() - c.blue()) ** 2
    return min(PALETTE, key=dist)


def head_center(size=(220, 380), pitch=0.0):
    import math
    scale = min(size[0] / 22.0, size[1] / 38.0)
    y = (28 - 16) * math.cos(math.radians(pitch))  # the model rotates about its middle (y=16)
    return size[0] / 2, size[1] / 2 - y * scale, scale


def test_front_back_and_sides(app):
    tex = head_texture()
    cx, cy, s = head_center()
    assert color_at(render(tex, 0, 0), cx + 1, cy + 1) == FRONT
    assert color_at(render(tex, 180, 0), cx + 1, cy + 1) == BACK
    # yaw +90 turns the character's right side (texture region at x=0) towards the viewer
    assert color_at(render(tex, 90, 0), cx + 1, cy + 1) == RIGHT
    assert color_at(render(tex, -90, 0), cx + 1, cy + 1) == LEFT


def test_top_and_bottom(app):
    tex = head_texture()
    # pitch +89 tilts the top of the head towards the viewer, -89 the bottom
    cx, cy, s = head_center(pitch=89)
    assert color_at(render(tex, 0, 89), cx + 1, cy + 1) == TOP
    cx, cy, s = head_center(pitch=-89)
    assert color_at(render(tex, 0, -89, head_only=True), cx + 1, cy + 1) == BOTTOM


def test_face_orientation_marker_top_left(app):
    tex = head_texture()
    cx, cy, s = head_center()
    img = render(tex, 0, 0)
    # top-left texel of the face is at the viewer's upper-left of the head
    assert color_at(img, cx - 3.5 * s, cy - 3.5 * s) == "#000000"
    assert color_at(img, cx + 3.5 * s, cy - 3.5 * s) == FRONT


def test_slim_arms_are_narrower(app):
    tex = skin_view.placeholder_skin()

    def width_at_arm_row(slim):
        img = render(tex, 0, 0, slim)
        cx, cy, s = head_center()
        y = int(img.height() / 2 - (20 - 16) * s)
        xs = [x for x in range(img.width()) if QColor.fromRgba(img.pixel(x, y)).alpha() > 0]
        return max(xs) - min(xs)

    assert width_at_arm_row(True) < width_at_arm_row(False)


def test_legacy_skin_renders_both_legs(app):
    tex = skin_view.placeholder_skin().copy(0, 0, 64, 32)
    img = render(tex, 0, 0)
    cx, cy, s = head_center()
    y = int(img.height() / 2 + (16 - 6) * s)
    assert QColor.fromRgba(img.pixel(int(cx - 2 * s), y)).alpha() > 0
    assert QColor.fromRgba(img.pixel(int(cx + 2 * s), y)).alpha() > 0


def test_load_skin_bytes_validation(app, tmp_path):
    from PySide6.QtCore import QBuffer, QByteArray, QIODevice

    def png(img):
        ba = QByteArray()
        buf = QBuffer(ba)
        buf.open(QIODevice.WriteOnly)
        img.save(buf, "PNG")
        return bytes(ba)

    img, model = skin_view.load_skin_bytes(png(skin_view.placeholder_skin()))
    assert (img.width(), img.height()) == (64, 64) and model == "classic"
    for bad in (b"", b"GIF89a", png(QImage(32, 32, QImage.Format_ARGB32)), b"\x89PNG\r\n\x1a\n" + b"\0" * 50):
        with pytest.raises(skin_view.SkinFileError):
            skin_view.load_skin_bytes(bad)
    with pytest.raises(skin_view.SkinFileError):
        skin_view.load_skin_bytes(b"\x89PNG\r\n\x1a\n" + b"\0" * (200 * 1024))


def test_widget_interaction(app):
    w = skin_view.SkinView()
    w.reduce_motion = True
    w.resize(260, 400)
    w.show()
    yaw = w.yaw
    from PySide6.QtCore import QPointF
    from PySide6.QtGui import QMouseEvent
    from PySide6.QtCore import QEvent
    press = QMouseEvent(QEvent.MouseButtonPress, QPointF(100, 100), QPointF(100, 100), Qt.LeftButton, Qt.LeftButton, Qt.NoModifier)
    move = QMouseEvent(QEvent.MouseMove, QPointF(150, 100), QPointF(150, 100), Qt.LeftButton, Qt.LeftButton, Qt.NoModifier)
    w.mousePressEvent(press)
    w.mouseMoveEvent(move)
    assert w.yaw != yaw
    assert not w.timer.isActive()  # reduce motion: no auto-rotation
    w.reset_view()
    assert w.yaw == -28.0
    w.grab()  # paints without errors
