"""Installer: themed artwork, a safe uninstaller, and (when NSIS is installed) a real build."""
import re
import shutil
import sys
from pathlib import Path

import pytest

from conftest import ROOT

INST = ROOT / "installer"
sys.path.insert(0, str(INST))


def test_artwork_sizes(tmp_path):
    from PIL import Image
    import make_art
    sys.argv = ["make_art", str(tmp_path)]
    make_art.main()
    assert Image.open(tmp_path / "welcome.bmp").size == (164, 314)
    assert Image.open(tmp_path / "header.bmp").size == (150, 57)
    ico = Image.open(tmp_path / "luminary.ico")
    assert {(16, 16), (32, 32), (48, 48), (256, 256)} <= set(ico.info["sizes"])


def test_nsis_script_is_per_user_and_uninstaller_never_wipes_the_folder():
    nsi = (INST / "luminary.nsi").read_text(encoding="utf-8")
    assert "RequestExecutionLevel user" in nsi and "$LOCALAPPDATA\\Programs\\Luminary" in nsi
    assert not re.search(r'RMDir\s+/r\s+"\$INSTDIR"\s*$', nsi, re.M)  # user may have picked C:\ or Desktop
    # upgrades must not touch player data
    install = nsi[nsi.index('Section "Luminary"'):nsi.index("SectionEnd")]
    for data in (".minecraft", "launcher_data", "logs", "runtime"):
        assert data not in install
    # shortcuts start the GUI-subsystem Python, never python.exe (console)
    assert 'CreateShortCut "$DESKTOP\\Luminary.lnk" "$INSTDIR\\python\\pythonw.exe"' in nsi
    assert "python\\python.exe" not in nsi


@pytest.mark.skipif(shutil.which("makensis") is None, reason="NSIS (makensis) not installed")
def test_installer_builds_from_a_prepared_folder(tmp_path):
    from test_package_release import fake_pe, prepared  # noqa: F401
    import subprocess
    src = tmp_path / "prepared"
    shutil.copytree(ROOT, src, ignore=shutil.ignore_patterns("__pycache__", ".pytest_cache"))
    for name, sub in (("Luminary.exe", 2), ("LuminaryLauncher.exe", 3), ("python/pythonw.exe", 2),
                      ("python/python.exe", 3), ("jre/bin/javaw.exe", 2)):
        fake_pe(src / name, sub)
    for pkg in ("PySide6", "mcstatus"):
        (src / "python" / "Lib" / "site-packages" / pkg).mkdir(parents=True)
    out = tmp_path / "out"
    r = subprocess.run([sys.executable, str(INST / "build_installer.py"), str(src), str(out), "--version", "2.0.0"],
                       capture_output=True, text=True, timeout=600)
    assert r.returncode == 0, r.stdout + r.stderr
    setup = out / "Luminary-Setup-2.0.0.exe"
    from package_release import pe_subsystem
    assert setup.stat().st_size > 100_000 and pe_subsystem(setup) == 2
