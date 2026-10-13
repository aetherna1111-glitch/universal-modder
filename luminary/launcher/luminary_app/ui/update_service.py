"""Background launcher self-update: check, download and stage; offer a restart to install."""
from __future__ import annotations

import logging
import subprocess

from PySide6.QtCore import QObject, QTimer, Signal

from .. import __version__, selfupdate
from ..launch import popen_kwargs

log = logging.getLogger("luminary.update")


class UpdateService(QObject):
    status = Signal(str)          # human-readable state for Settings
    staged = Signal(object)       # selfupdate.Staged - ready to install on restart

    def __init__(self, state, interval_ms: int = 6 * 3600 * 1000):
        super().__init__()
        self.state = state
        self.busy = False
        self.ready: selfupdate.Staged | None = None
        self.last_status = ""
        self.timer = QTimer(self)
        self.timer.setInterval(interval_ms)
        self.timer.timeout.connect(self.check)
        cat = state.catalog
        self.cfg = selfupdate.UpdateConfig(cat.update_url, cat.update_key) if cat.update_url else None

    @property
    def enabled(self) -> bool:
        return self.cfg is not None

    def _set(self, text: str) -> None:
        self.last_status = text
        self.status.emit(text)

    def start(self, first_delay_ms: int = 3_000) -> None:
        if not self.enabled:
            self._set("Автообновление не настроено")
            return
        pending = selfupdate.pending_version(self.state.paths.base)
        if pending:
            self._set(f"Версия {pending} установится при следующем запуске")
        QTimer.singleShot(first_delay_ms, self.check)
        self.timer.start()

    def check(self, manual: bool = False) -> None:
        if not self.enabled or self.busy or self.ready is not None:
            return
        self.busy = True
        self._set("Проверка обновлений…")
        cfg, base = self.cfg, self.state.paths.base

        def work(ctx):
            release = selfupdate.check(cfg, base, __version__)
            if release is None:
                return None
            ctx.progress(f"Загрузка версии {release.version}…")
            return selfupdate.stage(cfg, base, release, cancel=ctx.cancel)

        def done(staged):
            self.busy = False
            if staged is None:
                self._set(f"Установлена последняя версия ({__version__})")
                if manual:
                    self.state.toast.emit("Обновлений нет", "info")
                return
            self.ready = staged
            self._set(f"Готова версия {staged.version} — установится при перезапуске")
            log.info("launcher update %s staged (%d files)", staged.version, staged.changed)
            self.staged.emit(staged)

        def failed(exc):
            self.busy = False
            log.warning("update check failed: %s", exc)
            self._set(f"Не удалось проверить обновления: {exc}")
            if manual:
                self.state.toast.emit(str(exc), "error")

        self.state.runner.run(work, done, failed, lambda m: self._set(m), name="self-update")

    def restart_now(self) -> bool:
        """Start the launcher again (it installs the update first) and let the caller quit."""
        cmd = selfupdate.restart_command(self.state.paths.base)
        try:
            subprocess.Popen(cmd, cwd=str(self.state.paths.base), **popen_kwargs(subprocess.DEVNULL, subprocess.DEVNULL))
        except OSError as exc:
            log.error("restart failed: %s", exc)
            self.state.toast.emit("Не удалось перезапустить — обновление установится при следующем запуске", "error")
            return False
        return True
