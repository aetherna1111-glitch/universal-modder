"""GUI-only start: the starter exe is a Windows GUI program, and no child process can open a console."""
import re
import shutil
import struct
import subprocess
from pathlib import Path

import pytest

from conftest import ROOT

APP = ROOT / "luminary_app"


def pe_subsystem(path: Path) -> int:
    data = path.read_bytes()[:4096]
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    assert data[pe:pe + 4] == b"PE\0\0"
    return struct.unpack_from("<H", data, pe + 24 + 68)[0]


@pytest.mark.skipif(shutil.which("mcs") is None, reason="Mono C# compiler (mcs) not installed")
def test_starter_builds_as_windows_gui_program(tmp_path):
    out = tmp_path / "Luminary.exe"
    subprocess.run(["mcs", "-langversion:5", "-nologo", "-target:winexe", "-win32icon:" + str(ROOT / "luminary.ico"),
                    "-r:System.Windows.Forms.dll", "-out:" + str(out), str(ROOT / "starter" / "LuminaryStart.cs")],
                   check=True, capture_output=True)
    assert pe_subsystem(out) == 2  # IMAGE_SUBSYSTEM_WINDOWS_GUI: Windows creates no console for it


def test_build_script_targets_winexe():
    bat = (ROOT / "build_starter.bat").read_text()
    assert "/target:winexe" in bat and "LuminaryStart.cs" in bat and "/win32icon:luminary.ico" in bat


def test_run_bat_prefers_gui_starter_and_pythonw():
    bat = (ROOT / "run.bat").read_text().lower()
    assert "luminary.exe" in bat and "pythonw.exe" in bat and "python\\python.exe" not in bat


_CALL = re.compile(r"subprocess\.(Popen|run|call|check_call|check_output)\(")


def test_every_child_process_is_created_without_a_console():
    """Static guard: each subprocess call either uses launch.popen_kwargs (CREATE_NO_WINDOW) or sets
    creationflags itself, so no console window can appear when the launcher runs under pythonw."""
    offenders = []
    for py in APP.rglob("*.py"):
        lines = py.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if not _CALL.search(line) or "macOS only" in line:
                continue
            window = "\n".join(lines[max(0, i - 6): i + 3])
            if "popen_kwargs" not in window and "creationflags" not in window:
                offenders.append(f"{py.relative_to(ROOT)}:{i + 1}: {line.strip()}")
    assert offenders == []


def test_no_print_based_diagnostics():
    """pythonw has no stdout: diagnostics must go through logging (logs/launcher.log)."""
    hits = [f"{p.relative_to(ROOT)}:{n}" for p in APP.rglob("*.py")
            for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1)
            if re.match(r"\s*print\(", line)]
    assert hits == []
