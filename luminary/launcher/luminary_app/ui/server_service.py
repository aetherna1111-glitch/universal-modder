"""Shared, periodic server status (one request per minute for all screens, never on the GUI thread)."""
from __future__ import annotations

from PySide6.QtCore import QObject, QTimer, Signal

from ..server_status import ServerStatus, query


class ServerService(QObject):
    updated = Signal(object)   # ServerStatus
    checking = Signal()

    def __init__(self, state, interval_ms: int = 60_000):
        super().__init__()
        self.state = state
        self.last: ServerStatus | None = None
        self._busy = False
        self.timer = QTimer(self)
        self.timer.setInterval(interval_ms)
        self.timer.timeout.connect(self.refresh)

    def start(self) -> None:
        self.refresh()
        self.timer.start()

    def refresh(self) -> None:
        server = self.state.profile.server
        if server is None or self._busy:
            return
        self._busy = True
        self.checking.emit()

        def done(st):
            self._busy = False
            self.last = st
            self.updated.emit(st)

        def failed(_exc):
            self._busy = False

        self.state.runner.run(lambda ctx: query(server.host, server.port), done, failed, name="server-status")
