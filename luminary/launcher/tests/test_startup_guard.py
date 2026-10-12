"""launcher.py never exits silently: startup failures produce a message and a persistent log."""
import os
import shutil
import subprocess
import sys

import pytest

import launcher
from conftest import ROOT


@pytest.fixture
def shown(monkeypatch):
    messages = []
    monkeypatch.setattr(launcher, "_show", messages.append)
    return messages


def test_missing_component_is_explained_and_logged(shown, tmp_path, monkeypatch):
    monkeypatch.setattr(launcher, "BASE_DIR", str(tmp_path))

    def entry(argv):
        raise ModuleNotFoundError("No module named 'PySide6'", name="PySide6")

    assert launcher.run(entry) == 1
    assert "PySide6" in shown[0] and "Переустановите" in shown[0]
    log = tmp_path / "logs" / "startup-error.log"
    assert "ModuleNotFoundError" in log.read_text(encoding="utf-8") and str(log) in shown[0]


def test_read_only_folder_suggests_a_user_folder(shown, tmp_path, monkeypatch):
    monkeypatch.setattr(launcher, "BASE_DIR", str(tmp_path / "Program Files" / "Luminary"))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "appdata"))

    def entry(argv):
        raise PermissionError(13, "Permission denied")

    assert launcher.run(entry) == 1
    assert "Program Files" in shown[0] and "C:\\Games\\Luminary" in shown[0]


def test_log_falls_back_when_launcher_folder_is_not_writable(shown, tmp_path, monkeypatch):
    ro = tmp_path / "ro"
    ro.mkdir()
    (ro / "logs").write_text("a file where the logs folder should be")  # makedirs fails
    monkeypatch.setattr(launcher, "BASE_DIR", str(ro))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "appdata"))
    launcher.run(lambda argv: 1 / 0)
    assert (tmp_path / "appdata" / "Luminary" / "startup-error.log").exists()
    assert "ZeroDivisionError" in shown[0]


def test_normal_exit_codes_pass_through(shown):
    assert launcher.run(lambda argv: 0) == 0
    with pytest.raises(SystemExit):
        launcher.run(lambda argv: sys.exit(3))
    assert shown == []


def test_real_process_without_pyside6_shows_error_instead_of_silence(tmp_path):
    """Run launcher.py in a clean interpreter where PySide6 cannot be imported."""
    blocker = tmp_path / "blocker"
    blocker.mkdir()
    (blocker / "PySide6").mkdir()
    (blocker / "PySide6" / "__init__.py").write_text("raise ImportError('PySide6 is broken here', name='PySide6')")
    home = tmp_path / "home"
    shutil.copytree(ROOT / "luminary_app", home / "luminary_app")
    shutil.copy(ROOT / "launcher.py", home / "launcher.py")
    env = dict(os.environ, PYTHONPATH=str(blocker))
    out = subprocess.run([sys.executable, str(home / "launcher.py")], env=env, capture_output=True, text=True,
                         timeout=60)
    assert out.returncode == 1
    assert "PySide6" in out.stderr and "startup-error.log" in out.stderr
    assert "ImportError" in (home / "logs" / "startup-error.log").read_text(encoding="utf-8")
