"""Main window: custom title bar (frameless, natively movable/resizable), sidebar, pages, status bar."""
from __future__ import annotations

import logging

from PySide6.QtCore import QEvent, QPoint, QRect, QSize, Qt, QTimer
from PySide6.QtGui import QGuiApplication, QIcon, QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QButtonGroup, QFrame, QHBoxLayout, QLabel, QMessageBox, QPushButton,
                               QSizeGrip, QStackedWidget, QVBoxLayout, QWidget)

from .. import readiness
from . import icons, theme
from .dialogs import ErrorDialog, UpdateDialog
from .launch_controller import LaunchController
from .screens.friends import FriendsScreen
from .screens.home import HomeScreen
from .screens.mods import ModsScreen
from .screens.profile import ProfileScreen
from .screens.server import ServerScreen
from .screens.settings_screen import SettingsScreen
from .server_service import ServerService
from .widgets import Backdrop, StatusDot, Toast, button, label

log = logging.getLogger("luminary.window")
EDGE = 6

PAGES = [("home", "Главная", "home"), ("server", "Сервер", "server"), ("profile", "Профиль", "profile"),
         ("mods", "Моды", "mods"), ("friends", "Друзья", "friends"), ("settings", "Настройки", "settings")]


class TitleBar(QFrame):
    def __init__(self, window: "MainWindow"):
        super().__init__()
        self.win = window
        self.setFixedHeight(42)
        self.setStyleSheet(f"TitleBar, QFrame#TitleBar {{ background: {theme.PANEL}; border-bottom: 1px solid {theme.BORDER}; }}")
        self.setObjectName("TitleBar")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 0, 8, 0)
        lay.setSpacing(8)
        ic = QLabel()
        if window.state.paths.icon.exists():
            ic.setPixmap(QIcon(str(window.state.paths.icon)).pixmap(22, 22))
        lay.addWidget(ic)
        t = label("Luminary")
        t.setStyleSheet(f"color: {theme.ACCENT}; font-weight: 800; font-size: 14px; letter-spacing: 0.5px;")
        lay.addWidget(t)
        lay.addStretch()
        self.account = button("", "ghost", "profile", "Профиль")
        self.account.clicked.connect(lambda: window.go("profile"))
        lay.addWidget(self.account)
        self.btn_min = button("", "window", "minimize", "Свернуть")
        self.btn_max = button("", "window", "maximize", "Развернуть")
        self.btn_close = button("", "window", "close", "Закрыть")
        self.btn_close.setObjectName("CloseButton")
        for b in (self.btn_min, self.btn_max, self.btn_close):
            b.setFixedSize(36, 28)
            b.setFocusPolicy(Qt.TabFocus)
            lay.addWidget(b)
        self.btn_min.clicked.connect(window.showMinimized)
        self.btn_max.clicked.connect(window.toggle_max)
        self.btn_close.clicked.connect(window.close)

    def mousePressEvent(self, e) -> None:
        if e.button() == Qt.LeftButton and self.win.windowHandle() is not None:
            self.win.windowHandle().startSystemMove()

    def mouseDoubleClickEvent(self, e) -> None:
        if e.button() == Qt.LeftButton:
            self.win.toggle_max()


class MainWindow(QWidget):
    def __init__(self, state):
        super().__init__()
        self.state = state
        self.setObjectName("Root")
        self.setWindowTitle("Luminary Launcher")
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Window)
        self.setMinimumSize(1100, 660)
        if state.paths.icon.exists():
            self.setWindowIcon(QIcon(str(state.paths.icon)))
        self.controller = LaunchController(state, self)
        self.service = ServerService(state)

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        self.titlebar = TitleBar(self)
        root.addWidget(self.titlebar)
        middle = QHBoxLayout()
        middle.setSpacing(0)
        middle.addWidget(self._sidebar())
        self.stack = QStackedWidget()
        self.pages: dict[str, QWidget] = {}
        self.home = HomeScreen(state, self.controller, self.service)
        self.server = ServerScreen(state, self.service)
        self.profile = ProfileScreen(state)
        self.mods = ModsScreen(state, self.controller)
        self.friends = FriendsScreen(state)
        self.settings_page = SettingsScreen(state, self.controller, self.go)
        for key, page in (("home", self.home), ("server", self.server), ("profile", self.profile),
                          ("mods", self.mods), ("friends", self.friends), ("settings", self.settings_page)):
            self.pages[key] = page
            self.stack.addWidget(page)
        middle.addWidget(self.stack, 1)
        root.addLayout(middle, 1)
        root.addWidget(self._statusbar())

        self.toast = Toast(self)
        self.toast.reduce_motion = state.settings.reduce_motion
        state.toast.connect(self.toast.show_message)
        for page in (self.home, self.friends):
            page.go_profile.connect(lambda which: self.go("profile", which))

        self.controller.failed.connect(self.on_failed)
        self.controller.ask_update.connect(self.on_ask_update)
        self.controller.game_started.connect(self.on_game_started)
        self.controller.game_finished.connect(self.on_game_finished)
        self.controller.phase.connect(self.on_phase)
        state.account_changed.connect(self._update_account_chip)
        state.account_status.connect(lambda _s: self._update_account_chip())
        state.settings_changed.connect(self._setting_changed)
        self._shortcuts()
        self._restore_geometry()
        self._update_account_chip()
        QApplication.instance().installEventFilter(self)
        self._edge_cursor = False

    # ------------------------------------------------------------------ layout parts
    def _sidebar(self) -> QWidget:
        side = Backdrop(self.state.paths.assets_dir / "left_bg.png", 110, 120, anchor="bottom")
        side.setFixedWidth(220)
        lay = QVBoxLayout(side)
        lay.setContentsMargins(12, 16, 12, 16)
        lay.setSpacing(4)
        self.nav = QButtonGroup(self)
        self.nav.setExclusive(True)
        self.nav_buttons: dict[str, QPushButton] = {}
        for i, (key, text, ic) in enumerate(PAGES):
            b = QPushButton(text)
            b.setProperty("variant", "nav")
            b.setCheckable(True)
            b.setIcon(icons.icon(ic, theme.TEXT, theme.ACCENT, 18))
            b.setIconSize(QSize(18, 18))
            b.setCursor(Qt.PointingHandCursor)
            b.setToolTip(f"{text} (Ctrl+{i + 1})")
            b.setAccessibleName(text)
            b.clicked.connect(lambda _=False, k=key: self.go(k))
            self.nav.addButton(b)
            self.nav_buttons[key] = b
            lay.addWidget(b)
        lay.addStretch()
        exit_btn = QPushButton("Выход")
        exit_btn.setProperty("variant", "nav")
        exit_btn.setIcon(icons.icon("exit", theme.MUTED))
        exit_btn.setCursor(Qt.PointingHandCursor)
        exit_btn.clicked.connect(self.close)
        lay.addWidget(exit_btn)
        self.nav_buttons["home"].setChecked(True)
        return side

    def _statusbar(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("StatusBar")
        bar.setFixedHeight(34)
        bar.setStyleSheet(f"QFrame#StatusBar {{ background: {theme.PANEL}; border-top: 1px solid {theme.BORDER}; }}")
        lay = QHBoxLayout(bar)
        lay.setContentsMargins(14, 0, 4, 0)
        lay.setSpacing(10)
        p = self.state.profile
        self.sb_profile = label(f"Minecraft {p.minecraft}  ·  {p.loader_label()}", "caption")
        lay.addWidget(self.sb_profile)
        self.sb_pack = label("", "caption")
        lay.addWidget(self.sb_pack)
        lay.addStretch()
        self.sb_dot = StatusDot("pending", 9)
        self.sb_ready = label("Проверка…", "caption")
        lay.addWidget(self.sb_dot)
        lay.addWidget(self.sb_ready)
        grip = QSizeGrip(bar)
        grip.setFixedSize(14, 14)
        lay.addWidget(grip, 0, Qt.AlignBottom | Qt.AlignRight)
        return bar

    def _shortcuts(self) -> None:
        for i, (key, _t, _i) in enumerate(PAGES):
            QShortcut(QKeySequence(f"Ctrl+{i + 1}"), self, activated=lambda k=key: self.go(k))
        QShortcut(QKeySequence("Ctrl+Return"), self, activated=self._play_shortcut)
        QShortcut(QKeySequence("Ctrl+Enter"), self, activated=self._play_shortcut)
        QShortcut(QKeySequence("F5"), self, activated=self.service.refresh)

    def _play_shortcut(self) -> None:
        self.go("home")
        self.home.on_play()

    # ------------------------------------------------------------------ navigation
    def go(self, key: str, sub: str | None = None) -> None:
        page = self.pages.get(key)
        if page is None:
            return
        self.stack.setCurrentWidget(page)
        self.nav_buttons[key].setChecked(True)
        if key == "profile" and sub:
            self.profile.open(sub)

    def current_page(self) -> str:
        w = self.stack.currentWidget()
        return next(k for k, v in self.pages.items() if v is w)

    def toggle_max(self) -> None:
        if self.isMaximized():
            self.showNormal()
        else:
            self.showMaximized()

    def changeEvent(self, e) -> None:
        if e.type() == QEvent.WindowStateChange:
            maxed = self.isMaximized()
            self.titlebar.btn_max.setIcon(icons.icon("restore" if maxed else "maximize"))
            self.titlebar.btn_max.setToolTip("Восстановить" if maxed else "Развернуть")
        super().changeEvent(e)

    # ------------------------------------------------------------------ frameless resize
    def _edges_at(self, gpos: QPoint) -> Qt.Edges:
        if self.isMaximized() or self.isFullScreen():
            return Qt.Edges()
        r = self.frameGeometry()
        edges = Qt.Edges()
        if not r.adjusted(-1, -1, 1, 1).contains(gpos):
            return edges
        if gpos.x() - r.left() < EDGE:
            edges |= Qt.LeftEdge
        if r.right() - gpos.x() < EDGE:
            edges |= Qt.RightEdge
        if gpos.y() - r.top() < EDGE:
            edges |= Qt.TopEdge
        if r.bottom() - gpos.y() < EDGE:
            edges |= Qt.BottomEdge
        return edges

    def eventFilter(self, obj, e) -> bool:
        t = e.type()
        if t in (QEvent.MouseMove, QEvent.MouseButtonPress) and isinstance(obj, QWidget) and obj.window() is self:
            gpos = e.globalPosition().toPoint()
            edges = self._edges_at(gpos)
            if t == QEvent.MouseMove and not e.buttons():
                if edges:
                    shape = {Qt.LeftEdge: Qt.SizeHorCursor, Qt.RightEdge: Qt.SizeHorCursor,
                             Qt.TopEdge: Qt.SizeVerCursor, Qt.BottomEdge: Qt.SizeVerCursor}.get(edges)
                    if shape is None:
                        tl_br = edges in (Qt.LeftEdge | Qt.TopEdge, Qt.RightEdge | Qt.BottomEdge)
                        shape = Qt.SizeFDiagCursor if tl_br else Qt.SizeBDiagCursor
                    if self._edge_cursor:
                        QApplication.changeOverrideCursor(shape)
                    else:
                        QApplication.setOverrideCursor(shape)
                        self._edge_cursor = True
                elif self._edge_cursor:
                    QApplication.restoreOverrideCursor()
                    self._edge_cursor = False
            elif t == QEvent.MouseButtonPress and e.button() == Qt.LeftButton and edges and self.windowHandle():
                self.windowHandle().startSystemResize(edges)
                return True
        return super().eventFilter(obj, e)

    # ------------------------------------------------------------------ state reactions
    def _update_account_chip(self) -> None:
        st = self.state
        chip = self.titlebar.account
        if not st.accounts_enabled:
            chip.hide()
            return
        chip.show()
        if st.signed_in:
            chip.setText(((st.me or {}).get("profile") or {}).get("name", "Профиль"))
        elif st.status == "checking":
            chip.setText("Проверка…")
        elif st.status == "offline" and st.session:
            chip.setText(f"{st.session.get('username') or 'Аккаунт'} · нет связи")
        else:
            chip.setText("Войти")

    def _setting_changed(self, key: str) -> None:
        if key == "reduce_motion":
            self.toast.reduce_motion = self.state.settings.reduce_motion
        if key in ("java_path", "profile_id"):
            self.refresh_readiness()
        if key == "profile_id":
            p = self.state.profile
            self.sb_profile.setText(f"Minecraft {p.minecraft}  ·  {p.loader_label()}")
            self.service.refresh()

    def refresh_readiness(self) -> None:
        st = self.state
        java = self.controller.java_path()

        def done(r):
            text, level = r.summary()
            self.sb_ready.setText(text)
            self.sb_dot.set_state({"ok": "online", "warning": "warning", "error": "error"}[level])
            self.sb_pack.setText(f"·  сборка {r.pack_version}" if r.pack_version else "·  сборка не синхронизирована")

        st.runner.run(lambda ctx: readiness.check(st.paths, st.profile, java), done, name="readiness")

    def on_phase(self, phase: str) -> None:
        if phase == "running":
            self.sb_dot.set_state("playing")
            self.sb_ready.setText("Игра запущена")

    def on_failed(self, message: str, log_path) -> None:
        self.refresh_readiness()
        ErrorDialog(self, "Не удалось запустить игру", message, log_path=log_path,
                    logs_dir=self.state.paths.log_dir).exec()

    def on_ask_update(self, plan) -> None:
        if self.isMinimized():
            self.showNormal()
        accepted = UpdateDialog(self, plan).exec()
        self.controller.continue_with(bool(accepted))

    def on_game_started(self) -> None:
        mode = self.state.settings.after_launch
        if mode == "minimize":
            self.showMinimized()
        elif mode == "close":
            log.info("closing launcher after game start (setting)")
            QTimer.singleShot(1500, self.close)

    def on_game_finished(self, result) -> None:
        self.refresh_readiness()
        if self.isMinimized():
            self.showNormal()
            self.raise_()
            self.activateWindow()
        if result.exit_code == 0:
            self.state.toast.emit("Игра закрыта", "info")
            return
        self.state.sounds.play("error")
        msg = (f"Minecraft завершился с ошибкой (код {result.exit_code})."
               + (" Найден отчёт о сбое." if result.crash_report else "")
               + " Отправь журнал администрации, если ошибка повторяется.")
        ErrorDialog(self, "Игра вылетела", msg, log_path=result.log_path, crash_report=result.crash_report,
                    details=result.log_tail, logs_dir=self.state.paths.log_dir).exec()

    # ------------------------------------------------------------------ lifecycle
    def showEvent(self, e) -> None:
        super().showEvent(e)
        if not getattr(self, "_started", False):
            self._started = True
            self.state.sounds.play("open")
            self.service.start()
            self.state.restore_session()
            self.refresh_readiness()

    def _restore_geometry(self) -> None:
        g = self.state.settings.window or {}
        screen = QGuiApplication.primaryScreen()
        avail = screen.availableGeometry() if screen else QRect(0, 0, 1366, 768)
        w = max(self.minimumWidth(), min(int(g.get("w", 1300)), avail.width()))
        h = max(self.minimumHeight(), min(int(g.get("h", 740)), avail.height()))
        x, y = g.get("x"), g.get("y")
        if isinstance(x, int) and isinstance(y, int) and any(
                s.availableGeometry().intersects(QRect(x, y, w, h)) for s in QGuiApplication.screens()):
            self.setGeometry(x, y, w, h)
        else:
            self.resize(w, h)
            self.move(avail.center() - QPoint(w // 2, h // 2))
        self._start_maximized = bool(g.get("maximized"))

    def closeEvent(self, e) -> None:
        if self.controller.busy:
            if QMessageBox.question(self, "Идёт подготовка игры",
                                    "Прервать запуск и закрыть лаунчер? Файлы сборки останутся прежними.") \
                    != QMessageBox.Yes:
                e.ignore()
                return
            self.controller.cancel()
        geo = self.normalGeometry() if self.isMaximized() else self.geometry()
        self.state.settings.window = {"x": geo.x(), "y": geo.y(), "w": geo.width(), "h": geo.height(),
                                      "maximized": self.isMaximized()}
        self.state.save()
        if self.state.game_state == "running":
            log.info("launcher closed while the game keeps running")
        super().closeEvent(e)
