"""Entry point: logging, single instance, Qt application, main window."""
from __future__ import annotations

import argparse
import logging
import sys

from . import __version__, logs, profiles, settings as settings_mod
from .paths import Paths, default_base


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="launcher.py", description="Luminary Launcher")
    ap.add_argument("--console", action="store_true", help="also log to the terminal (development)")
    ap.add_argument("--debug", action="store_true", help="verbose log for this run")
    args, qt_args = ap.parse_known_args(argv if argv is not None else sys.argv[1:])

    paths = Paths(default_base())
    paths.ensure()
    settings = settings_mod.load(paths)
    log_file = logs.setup(paths.log_dir, debug=settings.debug_logging or args.debug, console=args.console)
    log = logging.getLogger("luminary")
    log.info("Luminary Launcher %s starting from %s", __version__, paths.base)

    from PySide6.QtCore import QLockFile, Qt
    from PySide6.QtGui import QFont, QIcon
    from PySide6.QtWidgets import QApplication, QMessageBox

    if sys.platform == "win32":
        # Started through pythonw.exe: without its own app id Windows groups the window under Python's icon.
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Luminary.Launcher")
        except Exception:
            log.debug("could not set AppUserModelID", exc_info=True)
    app = QApplication([sys.argv[0], *qt_args])
    app.setApplicationName("Luminary Launcher")
    app.setApplicationVersion(__version__)
    app.setStyle("Fusion")
    from .ui import theme
    app.setFont(QFont(theme.FONT_FAMILY.split(",")[0], 10))
    app.setStyleSheet(theme.stylesheet())
    if paths.icon.exists():
        app.setWindowIcon(QIcon(str(paths.icon)))
    logs.install_qt_message_handler()

    def report(summary: str) -> None:
        QMessageBox.critical(None, "Ошибка лаунчера",
                             f"Произошла непредвиденная ошибка:\n{summary}\n\nПодробности в {log_file}")
    logs.install_excepthooks(report)

    # Two launchers syncing the same .minecraft at once could corrupt the pack.
    lock = QLockFile(str(paths.data_dir / "launcher.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock(100):
        log.info("another launcher instance is running")
        QMessageBox.information(None, "Luminary", "Лаунчер уже запущен.")
        return 0

    try:
        catalog = profiles.load_catalog(paths.profiles_file)
    except (profiles.ProfileError, ValueError, OSError) as exc:
        log.error("profiles.json invalid: %s", exc)
        QMessageBox.warning(None, "Luminary", f"Файл profiles.json повреждён ({exc}).\n"
                                              "Используются стандартные настройки сервера Luminary.")
        catalog = profiles.parse_catalog(profiles.FALLBACK)
    for w in catalog.warnings:
        log.warning("profiles.json: %s", w)

    from .ui.main_window import MainWindow
    from .ui.sounds import Sounds
    from .ui.state import AppState
    from .ui.tasks import TaskRunner

    runner = TaskRunner()
    sounds = Sounds(paths.sounds_dir, settings.sounds)
    state = AppState(paths, catalog, settings, runner, sounds)
    window = MainWindow(state)
    if getattr(window, "_start_maximized", False):
        window.showMaximized()
    else:
        window.show()
    code = app.exec()
    runner.shutdown(3000)
    lock.unlock()
    log.info("launcher exited (%s)", code)
    return code
