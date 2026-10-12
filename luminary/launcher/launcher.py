# launcher.py - Luminary Launcher entry point (started by Luminary.exe, run.bat or `python launcher.py`).
#
# Uses only the standard library until the app is imported, so that a broken install (missing PySide6,
# read-only folder such as Program Files) still produces a visible error and a log file instead of a
# silent exit: pythonw.exe has no console to print to.
import os
import sys
import tempfile
import time
import traceback

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)


def _friendly(exc):
    if isinstance(exc, ImportError):
        name = getattr(exc, "name", None) or str(exc)
        return ("Не хватает компонента лаунчера: %s.\n\nПереустановите лаунчер целиком "
                "(папка python должна быть из того же архива)." % name)
    if isinstance(exc, PermissionError) or (isinstance(exc, OSError) and getattr(exc, "errno", None) in (13, 30)):
        return ("Нет прав на запись в папку лаунчера:\n%s\n\nРаспакуйте лаунчер в папку пользователя "
                "(например, C:\\Games\\Luminary), а не в Program Files." % BASE_DIR)
    return "Лаунчер не смог запуститься:\n%s: %s" % (type(exc).__name__, exc)


def _write_log(text):
    candidates = [os.path.join(BASE_DIR, "logs")]
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(os.path.join(os.environ["LOCALAPPDATA"], "Luminary"))
    candidates.append(os.path.join(tempfile.gettempdir(), "Luminary"))
    for d in candidates:
        try:
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, "startup-error.log")
            with open(path, "a", encoding="utf-8") as f:
                f.write(text)
            return path
        except OSError:
            continue
    return None


def _show(message):
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, message, "Luminary", 0x10)  # MB_ICONERROR
            return
        except Exception:
            pass
    if sys.stderr is not None:
        sys.stderr.write(message + "\n")


def report_startup_failure(exc):
    detail = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    log = _write_log("%s Luminary startup failed (Python %s, %s)\n%s\n"
                     % (time.strftime("%Y-%m-%d %H:%M:%S"), sys.version.split()[0], sys.executable, detail))
    message = _friendly(exc)
    if log:
        message += "\n\nПодробности: %s" % log
    _show(message)
    return 1


def _purge_app_modules():
    for name in list(sys.modules):
        if name == "luminary_app" or name.startswith("luminary_app."):
            del sys.modules[name]


def _apply_pending_update():
    """Install a staged launcher update before any app module is loaded. Never blocks startup."""
    try:
        from pathlib import Path
        from luminary_app import __version__, selfupdate
        return selfupdate.apply_pending(Path(BASE_DIR), __version__)
    except Exception as exc:  # noqa: BLE001
        _write_log("%s update apply failed: %r\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), exc))
        return None
    finally:
        _purge_app_modules()  # the next import must see the new files


def _rollback_update(exc):
    try:
        from pathlib import Path
        from luminary_app import selfupdate
        if not selfupdate.has_unconfirmed(Path(BASE_DIR)):
            return False
        bad = selfupdate.rollback(Path(BASE_DIR))
        _write_log("%s update %s failed to start (%r); rolled back\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), bad, exc))
        return True
    except Exception:  # noqa: BLE001
        return False
    finally:
        _purge_app_modules()


def run(entry=None, argv=None):
    if entry is None:
        _apply_pending_update()
    try:
        if entry is None:
            try:
                from luminary_app.main import main as entry
            except Exception as exc:  # noqa: BLE001 - a broken update must not brick the launcher
                if not _rollback_update(exc):
                    raise
                from luminary_app.main import main as entry
        return entry(argv)
    except SystemExit:
        raise
    except BaseException as exc:  # noqa: BLE001 - last resort: show it, never die silently
        if isinstance(exc, KeyboardInterrupt):
            return 130
        if _rollback_update(exc):
            _show("Новая версия лаунчера не запустилась и была откатена к предыдущей.\n"
                  "Запусти лаунчер ещё раз.")
            return 1
        return report_startup_failure(exc)


if __name__ == "__main__":
    sys.exit(run())
