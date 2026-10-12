"""Diagnostics without a console.

pythonw.exe (no console) sets sys.stdout/sys.stderr to None, so print() output and tracebacks of the
1.x launcher simply vanished. Here everything goes to logs/launcher.log (rotated), uncaught exceptions
are logged and reported, and stdout/stderr are redirected into the log when they do not exist.
Run `python launcher.py --console` (or set LUMINARY_DEBUG=1) to also log to the terminal while developing.
"""
from __future__ import annotations

import io
import logging
import logging.handlers
import os
import re
import sys
import threading
from pathlib import Path

LOG_FORMAT = "%(asctime)s %(levelname)-7s %(name)s: %(message)s"
_SECRET_RE = re.compile(r"(?i)(access_?token|authorization|bearer|password|session)([\"'=:\s]+)([^\s\"',}]{6,})")


class RedactingFormatter(logging.Formatter):
    """Last line of defence: mask anything that looks like a token or password in log text."""

    def format(self, record: logging.LogRecord) -> str:
        return redact(super().format(record))


def redact(text: str) -> str:
    return _SECRET_RE.sub(lambda m: f"{m.group(1)}{m.group(2)}***", text)


class _LoggerWriter(io.TextIOBase):
    def __init__(self, logger: logging.Logger, level: int):
        self._logger, self._level, self._buf = logger, level, ""

    def write(self, s: str) -> int:
        self._buf += s
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            if line.strip():
                self._logger.log(self._level, line.rstrip())
        return len(s)

    def flush(self) -> None:
        if self._buf.strip():
            self._logger.log(self._level, self._buf.rstrip())
        self._buf = ""


def setup(log_dir: Path, debug: bool = False, console: bool = False) -> Path:
    log_dir.mkdir(parents=True, exist_ok=True)
    path = log_dir / "launcher.log"
    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
    root.setLevel(logging.DEBUG if debug else logging.INFO)
    fh = logging.handlers.RotatingFileHandler(path, maxBytes=1_000_000, backupCount=5, encoding="utf-8")
    fh.setFormatter(RedactingFormatter(LOG_FORMAT))
    root.addHandler(fh)
    console = console or os.environ.get("LUMINARY_DEBUG") == "1"
    if console and sys.stderr is not None:
        sh = logging.StreamHandler(sys.stderr)
        sh.setFormatter(RedactingFormatter(LOG_FORMAT))
        root.addHandler(sh)
    if sys.stdout is None:
        sys.stdout = _LoggerWriter(logging.getLogger("stdout"), logging.INFO)
    if sys.stderr is None:
        sys.stderr = _LoggerWriter(logging.getLogger("stderr"), logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    return path


def install_excepthooks(on_error=None) -> None:
    """Log uncaught exceptions from any thread; on_error(summary) may show a dialog (main thread only)."""
    log = logging.getLogger("luminary.crash")

    def hook(exc_type, exc, tb):
        if issubclass(exc_type, KeyboardInterrupt):
            return
        log.critical("uncaught exception", exc_info=(exc_type, exc, tb))
        if on_error is not None:
            try:
                on_error(f"{exc_type.__name__}: {exc}")
            except Exception:
                log.exception("error handler failed")

    def thread_hook(args):
        log.critical("uncaught exception in thread %s", args.thread.name if args.thread else "?",
                     exc_info=(args.exc_type, args.exc_value, args.exc_traceback))

    sys.excepthook = hook
    threading.excepthook = thread_hook


def install_qt_message_handler() -> None:
    from PySide6.QtCore import QtMsgType, qInstallMessageHandler

    log = logging.getLogger("qt")
    levels = {QtMsgType.QtDebugMsg: logging.DEBUG, QtMsgType.QtInfoMsg: logging.INFO,
              QtMsgType.QtWarningMsg: logging.WARNING, QtMsgType.QtCriticalMsg: logging.ERROR,
              QtMsgType.QtFatalMsg: logging.CRITICAL}

    def handler(mode, context, message):
        log.log(levels.get(mode, logging.INFO), message)

    qInstallMessageHandler(handler)


def tail(path: Path, max_lines: int = 40, max_bytes: int = 64_000) -> str:
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - max_bytes))
            data = f.read().decode("utf-8", errors="replace")
    except OSError:
        return ""
    return redact("\n".join(data.splitlines()[-max_lines:]))
