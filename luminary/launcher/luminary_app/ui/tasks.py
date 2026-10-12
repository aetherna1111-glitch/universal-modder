"""Run blocking work (network, disk, processes) off the GUI thread, with results back on it."""
from __future__ import annotations

import logging
import threading
from typing import Any, Callable

from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, Signal

try:
    from shiboken6 import isValid as _qt_alive
except ImportError:  # pragma: no cover
    def _qt_alive(obj) -> bool:
        return True

log = logging.getLogger("luminary.tasks")


class TaskContext:
    def __init__(self, signals: "_Signals"):
        self.cancel = threading.Event()
        self._signals = signals

    @property
    def cancelled(self) -> bool:
        return self.cancel.is_set()

    def progress(self, value: Any) -> None:
        if not self.cancel.is_set():
            self._signals.progress.emit(value)


class _Signals(QObject):
    done = Signal(object)
    failed = Signal(object)
    progress = Signal(object)
    finished = Signal()


class _Runnable(QRunnable):
    def __init__(self, fn: Callable[[TaskContext], Any], ctx: TaskContext, signals: _Signals, name: str):
        super().__init__()
        self.setAutoDelete(True)
        self.fn, self.ctx, self.signals, self.name = fn, ctx, signals, name

    def run(self) -> None:
        try:
            result = self.fn(self.ctx)
        except Exception as exc:  # reported to the caller, never swallowed
            if not self.ctx.cancelled:
                log.debug("task %s failed: %s", self.name, exc, exc_info=True)
            self._emit(self.signals.failed, exc)
        else:
            self._emit(self.signals.done, result)
        finally:
            self._emit(self.signals.finished)

    def _emit(self, signal, *args) -> None:
        try:
            signal.emit(*args)
        except RuntimeError:
            # The receiver side was destroyed (application shutting down); nothing is waiting any more.
            log.debug("task %s finished after its receiver was destroyed", self.name)


class TaskHandle:
    def __init__(self, ctx: TaskContext, signals: _Signals):
        self.ctx = ctx
        self.signals = signals
        self.running = True

    def cancel(self) -> None:
        self.ctx.cancel.set()


class TaskRunner(QObject):
    def __init__(self, parent: QObject | None = None, max_threads: int = 4):
        super().__init__(parent)
        self.pool = QThreadPool(self)
        self.pool.setMaxThreadCount(max_threads)
        self._active: set[TaskHandle] = set()

    def run(self, fn: Callable[[TaskContext], Any], on_done: Callable[[Any], None] | None = None,
            on_error: Callable[[Exception], None] | None = None, on_progress: Callable[[Any], None] | None = None,
            owner: QObject | None = None, name: str = "task") -> TaskHandle:
        signals = _Signals()  # created on the GUI thread, so its slots run there
        ctx = TaskContext(signals)
        handle = TaskHandle(ctx, signals)

        def guard(cb):
            def call(value):
                if handle.ctx.cancelled and cb is not on_error:
                    return
                if owner is not None and not _qt_alive(owner):
                    return
                try:
                    cb(value)
                except Exception:
                    log.exception("callback of task %s failed", name)
            return call

        if on_done:
            signals.done.connect(guard(on_done), Qt.QueuedConnection)
        if on_error:
            def err(exc, cb=guard(on_error)):
                if not handle.ctx.cancelled:
                    cb(exc)
            signals.failed.connect(err, Qt.QueuedConnection)
        else:
            signals.failed.connect(lambda exc: log.error("task %s failed: %s", name, exc), Qt.QueuedConnection)
        if on_progress:
            signals.progress.connect(guard(on_progress), Qt.QueuedConnection)

        def finished():
            handle.running = False
            self._active.discard(handle)
        signals.finished.connect(finished, Qt.QueuedConnection)
        self._active.add(handle)
        self.pool.start(_Runnable(fn, ctx, signals, name))
        return handle

    def shutdown(self, timeout_ms: int = 3000) -> bool:
        for h in list(self._active):
            h.cancel()
        self.pool.clear()
        return self.pool.waitForDone(timeout_ms)
