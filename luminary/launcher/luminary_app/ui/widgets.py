"""Reusable widgets: backdrop panels, cards, state views, segmented control, toasts."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QEasingCurve, QPropertyAnimation, QRect, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QLinearGradient, QPainter, QPixmap
from PySide6.QtWidgets import (QButtonGroup, QFrame, QGraphicsOpacityEffect, QHBoxLayout, QLabel, QLineEdit,
                               QProgressBar, QPushButton, QSizePolicy, QToolButton, QVBoxLayout, QWidget)

from . import icons, theme


def label(text: str = "", role: str | None = None, wrap: bool = False, selectable: bool = False) -> QLabel:
    lb = QLabel(text)
    if role:
        lb.setProperty("role", role)
    lb.setWordWrap(wrap)
    if selectable:
        lb.setTextInteractionFlags(Qt.TextSelectableByMouse)
    return lb


def button(text: str, variant: str | None = None, icon_name: str | None = None, tooltip: str = "",
           icon_color: str | None = None) -> QPushButton:
    b = QPushButton(text)
    if variant:
        b.setProperty("variant", variant)
    if icon_name:
        color = icon_color or (theme.BG if variant in ("primary", "play") else theme.TEXT)
        b.setIcon(icons.icon(icon_name, color))
        b.setIconSize(QSize(16, 16))
    if tooltip:
        b.setToolTip(tooltip)
        b.setAccessibleName(tooltip)
    b.setCursor(Qt.PointingHandCursor)
    return b


def repolish(w: QWidget) -> None:
    w.style().unpolish(w)
    w.style().polish(w)
    w.update()


def set_invalid(edit: QLineEdit, invalid: bool) -> None:
    if bool(edit.property("invalid")) != invalid:
        edit.setProperty("invalid", invalid)
        repolish(edit)


def divider() -> QFrame:
    f = QFrame()
    f.setObjectName("Divider")
    return f


class Card(QFrame):
    def __init__(self, title: str | None = None, parent: QWidget | None = None, flat: bool = False,
                 padding: int = 16, spacing: int = 10):
        super().__init__(parent)
        self.setObjectName("CardFlat" if flat else "Card")
        self.body = QVBoxLayout(self)
        self.body.setContentsMargins(padding, padding - 2, padding, padding)
        self.body.setSpacing(spacing)
        self.header = None
        if title:
            self.header = QHBoxLayout()
            self.header.setSpacing(8)
            self.title_label = label(title.upper(), "section")
            self.header.addWidget(self.title_label)
            self.header.addStretch()
            self.body.addLayout(self.header)

    def add_header_widget(self, w: QWidget) -> None:
        if self.header is not None:
            self.header.addWidget(w)


class Backdrop(QWidget):
    """Paints an image scaled to cover the widget plus a darkening gradient. Scaled once per size."""

    def __init__(self, image: Path | None, top_alpha: int = 70, bottom_alpha: int = 200,
                 parent: QWidget | None = None, anchor: str = "center"):
        super().__init__(parent)
        self._src = QPixmap(str(image)) if image and Path(image).exists() else QPixmap()
        self._cache: QPixmap | None = None
        self._cache_size = QSize()
        self._top, self._bottom = top_alpha, bottom_alpha
        self._anchor = anchor
        self.setAttribute(Qt.WA_OpaquePaintEvent, True)

    def _scaled(self) -> QPixmap | None:
        if self._src.isNull():
            return None
        size = self.size() * self.devicePixelRatioF()
        if self._cache is None or self._cache_size != size:
            self._cache = self._src.scaled(size, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
            self._cache.setDevicePixelRatio(self.devicePixelRatioF())
            self._cache_size = size
        return self._cache

    def paintEvent(self, _e) -> None:
        p = QPainter(self)
        p.fillRect(self.rect(), QColor(theme.PANEL))
        pm = self._scaled()
        if pm is not None:
            w = pm.width() / pm.devicePixelRatio()
            h = pm.height() / pm.devicePixelRatio()
            x = (self.width() - w) / 2
            y = (self.height() - h) if self._anchor == "bottom" else (self.height() - h) / 2
            p.drawPixmap(int(x), int(y), pm)
        g = QLinearGradient(0, 0, 0, self.height())
        g.setColorAt(0, QColor(10, 20, 24, self._top))
        g.setColorAt(1, QColor(10, 20, 24, self._bottom))
        p.fillRect(self.rect(), g)
        p.end()


class ScaledImage(QLabel):
    """An image that scales down to the available width, keeping its aspect ratio (no cropping)."""

    def __init__(self, image: Path, max_height: int | None = None, parent: QWidget | None = None):
        super().__init__(parent)
        self._src = QPixmap(str(image)) if Path(image).exists() else QPixmap()
        self._max_h = max_height
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        self.setMinimumWidth(1)
        self._last_w = -1

    def hasHeightForWidth(self) -> bool:
        return not self._src.isNull()

    def heightForWidth(self, w: int) -> int:
        if self._src.isNull():
            return 0
        h = int(w * self._src.height() / self._src.width())
        return min(h, self._max_h) if self._max_h else h

    def sizeHint(self) -> QSize:
        if self._src.isNull():
            return QSize(0, 0)
        return QSize(self._src.width(), self.heightForWidth(self._src.width()))

    def resizeEvent(self, e) -> None:
        super().resizeEvent(e)
        if self._src.isNull() or self.width() == self._last_w:
            return
        self._last_w = self.width()
        dpr = self.devicePixelRatioF()
        target = QSize(self.width(), self.heightForWidth(self.width())) * dpr
        pm = self._src.scaled(target, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        pm.setDevicePixelRatio(dpr)
        self.setPixmap(pm)


class StatusDot(QLabel):
    COLORS = {"online": theme.GREEN, "playing": theme.ACCENT, "offline": theme.SUBTLE, "error": theme.RED,
              "warning": theme.YELLOW, "pending": theme.MUTED}

    def __init__(self, state: str = "pending", size: int = 10, parent: QWidget | None = None):
        super().__init__(parent)
        self._size = size
        self.setFixedSize(size, size)
        self.set_state(state)

    def set_state(self, state: str) -> None:
        self.setPixmap(icons.pixmap("dot", self._size, self.COLORS.get(state, theme.MUTED)))
        self.setAccessibleName(state)


class StateView(QWidget):
    """Loading / empty / error placeholder with an optional action button."""

    action = Signal()

    def __init__(self, parent: QWidget | None = None, compact: bool = False):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 24 if not compact else 8, 16, 24 if not compact else 8)
        lay.setSpacing(10)
        lay.addStretch()
        self.icon = QLabel()
        self.icon.setAlignment(Qt.AlignCenter)
        self.title = label("", "title")
        self.title.setAlignment(Qt.AlignCenter)
        self.title.setWordWrap(True)
        self.text = label("", "muted", wrap=True)
        self.text.setAlignment(Qt.AlignCenter)
        self.progress = QProgressBar()
        self.progress.setRange(0, 0)
        self.progress.setFixedWidth(160)
        self.btn = button("", "primary")
        self.btn.clicked.connect(self.action)
        for w in (self.icon, self.title, self.text):
            lay.addWidget(w)
        lay.addWidget(self.progress, 0, Qt.AlignCenter)
        lay.addWidget(self.btn, 0, Qt.AlignCenter)
        lay.addStretch()
        self.compact = compact

    def _show(self, icon_name, color, title, text, action, busy) -> None:
        size = 28 if self.compact else 40
        self.icon.setPixmap(icons.pixmap(icon_name, size, color) if icon_name else QPixmap())
        self.icon.setVisible(bool(icon_name))
        self.title.setText(title)
        self.title.setVisible(bool(title))
        self.text.setText(text)
        self.text.setVisible(bool(text))
        self.progress.setVisible(busy)
        self.btn.setText(action or "")
        self.btn.setVisible(bool(action))

    def loading(self, text: str = "Загрузка…") -> None:
        self._show(None, None, "", text, None, True)

    def empty(self, title: str, text: str = "", action: str | None = None, icon_name: str = "box") -> None:
        self._show(icon_name, theme.SUBTLE, title, text, action, False)

    def error(self, title: str, text: str = "", action: str | None = "Повторить", icon_name: str = "alert") -> None:
        self._show(icon_name, theme.RED, title, text, action, False)


class Segmented(QWidget):
    """Exclusive set of toggle buttons (keyboard: Tab to focus, arrows to move, Space to pick)."""

    changed = Signal(object)

    def __init__(self, parent: QWidget | None = None, columns: int = 4):
        super().__init__(parent)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        self.grid = QHBoxLayout(self)
        self.grid.setContentsMargins(0, 0, 0, 0)
        self.grid.setSpacing(6)
        self._values: dict[int, object] = {}
        self.group.idClicked.connect(lambda i: self.changed.emit(self._values[i]))

    def set_options(self, options: list[tuple[str, object]], current: object = None) -> None:
        for b in self.group.buttons():
            self.group.removeButton(b)
            b.deleteLater()
        self._values.clear()
        for i, (text, value) in enumerate(options):
            b = QPushButton(text)
            b.setProperty("variant", "segment")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            b.setAccessibleName(text)
            self.group.addButton(b, i)
            self._values[i] = value
            self.grid.addWidget(b)
            if value == current:
                b.setChecked(True)

    def value(self):
        i = self.group.checkedId()
        return self._values.get(i)

    def set_value(self, value) -> None:
        for i, v in self._values.items():
            if v == value:
                self.group.button(i).setChecked(True)


class Toast(QFrame):
    """Short, non-blocking confirmation in the bottom-right corner."""

    def __init__(self, parent: QWidget):
        super().__init__(parent)
        self.setObjectName("CardFlat")
        self.setAttribute(Qt.WA_TransparentForMouseEvents, True)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 10, 16, 10)
        lay.setSpacing(8)
        self.icon = QLabel()
        self.text = label("")
        lay.addWidget(self.icon)
        lay.addWidget(self.text)
        self.effect = QGraphicsOpacityEffect(self)
        self.setGraphicsEffect(self.effect)
        self.anim = QPropertyAnimation(self.effect, b"opacity", self)
        self.anim.setDuration(180)
        self.anim.setEasingCurve(QEasingCurve.OutCubic)
        self.timer = QTimer(self)
        self.timer.setSingleShot(True)
        self.timer.timeout.connect(self._hide)
        self.hide()
        self.reduce_motion = False

    def show_message(self, text: str, kind: str = "ok", ms: int = 2600) -> None:
        name, color = {"ok": ("check", theme.GREEN), "error": ("alert", theme.RED),
                       "info": ("dot", theme.ACCENT)}.get(kind, ("dot", theme.ACCENT))
        self.icon.setPixmap(icons.pixmap(name, 16, color))
        self.text.setText(text)
        self.adjustSize()
        par = self.parentWidget()
        self.move(par.width() - self.width() - 24, par.height() - self.height() - 56)
        self.raise_()
        self.show()
        self.anim.stop()
        if self.reduce_motion:
            self.effect.setOpacity(1.0)
        else:
            self.anim.setStartValue(0.0)
            self.anim.setEndValue(1.0)
            self.anim.start()
        self.timer.start(ms)

    def _hide(self) -> None:
        self.hide()


class PasswordEdit(QWidget):
    returnPressed = Signal()

    def __init__(self, placeholder: str = "Пароль", parent: QWidget | None = None):
        super().__init__(parent)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(6)
        self.edit = QLineEdit()
        self.edit.setEchoMode(QLineEdit.Password)
        self.edit.setPlaceholderText(placeholder)
        self.edit.setAccessibleName(placeholder)
        self.edit.setMaxLength(128)
        self.edit.returnPressed.connect(self.returnPressed)
        self.toggle = QToolButton()
        self.toggle.setCheckable(True)
        self.toggle.setIcon(icons.icon("eye", theme.MUTED))
        self.toggle.setToolTip("Показать пароль")
        self.toggle.setAccessibleName("Показать пароль")
        self.toggle.setCursor(Qt.PointingHandCursor)
        self.toggle.setStyleSheet("QToolButton { background: transparent; border: none; padding: 6px; }")
        self.toggle.toggled.connect(self._toggled)
        lay.addWidget(self.edit, 1)
        lay.addWidget(self.toggle)

    def _toggled(self, on: bool) -> None:
        self.edit.setEchoMode(QLineEdit.Normal if on else QLineEdit.Password)
        self.toggle.setIcon(icons.icon("eye_off" if on else "eye", theme.MUTED))
        self.toggle.setToolTip("Скрыть пароль" if on else "Показать пароль")

    def text(self) -> str:
        return self.edit.text()

    def clear(self) -> None:
        self.edit.clear()

    def setFocus(self) -> None:  # noqa: N802
        self.edit.setFocus()


def clear_layout(layout) -> None:
    while layout.count():
        item = layout.takeAt(0)
        w = item.widget()
        if w is not None:
            w.deleteLater()
        elif item.layout() is not None:
            clear_layout(item.layout())
