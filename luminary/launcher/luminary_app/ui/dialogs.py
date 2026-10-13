"""Dialogs: modpack update, launch/game errors with logs, password change, player profile."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QDesktopServices, QGuiApplication, QImage
from PySide6.QtWidgets import (QDialog, QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QPlainTextEdit,
                               QVBoxLayout, QWidget)

from .. import settings as settings_mod
from ..api import ApiError
from . import theme
from .widgets import PasswordEdit, StatusDot, button, label


def open_path(path: Path) -> bool:
    """Open a file or folder with the system handler (folders are created if missing)."""
    if not path.exists() and not path.suffix:
        path.mkdir(parents=True, exist_ok=True)
    return QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))


class _Base(QDialog):
    def __init__(self, parent: QWidget | None, title: str, width: int = 520):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setMinimumWidth(width)
        self.lay = QVBoxLayout(self)
        self.lay.setContentsMargins(24, 22, 24, 20)
        self.lay.setSpacing(12)
        heading = label(title, "title")
        self.lay.addWidget(heading)

    def buttons(self, *widgets) -> QHBoxLayout:
        row = QHBoxLayout()
        row.addStretch()
        for w in widgets:
            row.addWidget(w)
        self.lay.addLayout(row)
        return row


class UpdateDialog(_Base):
    def __init__(self, parent, plan):
        super().__init__(parent, "Доступно обновление сборки", 560)
        mb = plan.download_bytes / 1024 / 1024
        self.lay.addWidget(label(f"Версия {plan.manifest.version}", "muted"))
        summary = label(f"Скачать: <b>{len(plan.to_download)}</b> файл(ов), {mb:.1f} МБ<br>"
                        f"Убрать из сборки: <b>{len(plan.to_delete)}</b> файл(ов) "
                        f"(копии сохраняются в .minecraft/.backup)" +
                        (f"<br>Ваших изменённых файлов, которые заменит сборка: <b>{len(plan.backup_first)}</b> "
                         f"(ваши версии тоже сохранятся в .backup)" if getattr(plan, "backup_first", None) else ""))
        summary.setTextFormat(Qt.RichText)
        self.lay.addWidget(summary)
        lst = QListWidget()
        lst.setMinimumHeight(200)
        lst.setAccessibleName("Список изменений")
        for f in plan.to_download[:300]:
            it = QListWidgetItem(f"+  {f.path}")
            it.setForeground(Qt.GlobalColor.green)
            lst.addItem(it)
        for rel in plan.to_delete[:300]:
            it = QListWidgetItem(f"−  {rel}")
            it.setForeground(Qt.GlobalColor.red)
            lst.addItem(it)
        self.lay.addWidget(lst, 1)
        blocking = bool(getattr(plan, "blocking", False))
        if blocking:  # mods differ from the server's: it would refuse the join, so there is nothing to skip to
            self.lay.addWidget(label("Моды отличаются от серверных: без этого обновления сервер не пустит в игру.",
                                     "caption", wrap=True))
            skip = button("Отмена", "ghost")
        else:
            self.lay.addWidget(label("Меняются только настройки и ресурсы: можно сыграть и без обновления.",
                                     "caption", wrap=True))
            skip = button("Играть без обновления", "ghost")
        skip.clicked.connect(self.reject)
        ok = button("Обновить и играть", "primary", "upload")
        ok.clicked.connect(self.accept)
        ok.setDefault(True)
        self.buttons(skip, ok)


class ErrorDialog(_Base):
    def __init__(self, parent, title: str, message: str, log_path: Path | None = None,
                 crash_report: Path | None = None, details: str = "", logs_dir: Path | None = None):
        super().__init__(parent, title, 600)
        msg = label(message, wrap=True, selectable=True)
        msg.setProperty("role", "body")
        self.lay.addWidget(msg)
        if details:
            box = QPlainTextEdit(details)
            box.setReadOnly(True)
            box.setMinimumHeight(180)
            box.setStyleSheet(f"font-family: Consolas, 'DejaVu Sans Mono', monospace; font-size: 11px; color: {theme.MUTED};")
            self.lay.addWidget(box, 1)
        widgets = []
        if crash_report is not None:
            b = button("Отчёт о сбое", None, "log")
            b.clicked.connect(lambda: open_path(crash_report))
            widgets.append(b)
        if log_path is not None:
            b = button("Открыть журнал", None, "log")
            b.clicked.connect(lambda: open_path(log_path))
            widgets.append(b)
        if logs_dir is not None:
            b = button("Папка журналов", "ghost", "folder")
            b.clicked.connect(lambda: open_path(logs_dir))
            widgets.append(b)
        close = button("Закрыть", "primary")
        close.clicked.connect(self.accept)
        close.setDefault(True)
        widgets.append(close)
        self.buttons(*widgets)


class ChangePasswordDialog(_Base):
    def __init__(self, parent, state):
        super().__init__(parent, "Смена пароля", 420)
        self.state = state
        self.old = PasswordEdit("Текущий пароль")
        self.new = PasswordEdit("Новый пароль (от 8 символов)")
        self.rep = PasswordEdit("Повтори новый пароль")
        for w in (self.old, self.new, self.rep):
            self.lay.addWidget(w)
        self.rep.returnPressed.connect(self.submit)
        self.lay.addWidget(label("Остальные устройства будут разлогинены.", "caption"))
        self.error = label("", "error", wrap=True)
        self.lay.addWidget(self.error)
        cancel = button("Отмена", "ghost")
        cancel.clicked.connect(self.reject)
        self.ok = button("Сменить пароль", "primary")
        self.ok.clicked.connect(self.submit)
        self.buttons(cancel, self.ok)

    def submit(self) -> None:
        old, new, rep = self.old.text(), self.new.text(), self.rep.text()
        if not old or not new:
            self.error.setText("Заполни все поля")
            return
        if new != rep:
            self.error.setText("Новые пароли не совпадают")
            return
        if len(new) < 8:
            self.error.setText("Пароль должен быть не короче 8 символов")
            return
        self.ok.setEnabled(False)
        self.error.setText("")
        api = self.state.api()

        def failed(exc):
            self.ok.setEnabled(True)
            self.error.setText(exc.message if isinstance(exc, ApiError) else "Не удалось сменить пароль")

        def done(_):
            self.state.toast.emit("Пароль изменён", "ok")
            self.accept()

        self.state.runner.run(lambda ctx: api.change_password(old, new), done, failed, owner=self, name="password")


class PlayerDialog(_Base):
    """Another player's public profile with their skin in 3D."""

    def __init__(self, parent, state, data: dict):
        super().__init__(parent, data.get("name", "Игрок"), 460)
        from .skin_view import SkinView, load_skin_bytes
        self.view = SkinView()
        self.view.reduce_motion = state.settings.reduce_motion
        self.view.setMinimumHeight(340)
        self.lay.addWidget(self.view, 1)
        row = QHBoxLayout()
        presence = data.get("presence")
        if presence:
            dot = StatusDot(presence)
            row.addWidget(dot)
            row.addWidget(label({"playing": "Играет на сервере", "online": "В лаунчере",
                                 "offline": "Не в сети"}[presence], "muted"))
        row.addStretch()
        uuid = data.get("uuid", "")
        row.addWidget(label(f"UUID {uuid[:8]}…", "caption"))
        self.lay.addLayout(row)
        self.status = label("Загрузка скина…" if data.get("skin_url") else "Скин не установлен", "caption")
        self.lay.addWidget(self.status)
        close = button("Закрыть", "primary")
        close.clicked.connect(self.accept)
        self.buttons(close)
        url = data.get("skin_url")
        if url:
            api = state.api()

            def done(png):
                try:
                    img, _ = load_skin_bytes(png)
                except ValueError as exc:
                    self.status.setText(str(exc))
                    return
                self.view.set_skin(img, data.get("skin_model") == "slim")
                self.status.setText("Модель: " + ("тонкая (Alex)" if data.get("skin_model") == "slim" else "классическая (Steve)"))

            state.runner.run(lambda ctx: api.fetch_texture(url), done,
                             lambda e: self.status.setText(str(e)), owner=self, name="friend-skin")


def copy_text(text: str) -> None:
    QGuiApplication.clipboard().setText(text)
