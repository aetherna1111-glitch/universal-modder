"""package_release.py: a verified, reproducible Windows distribution without user data or dev endpoints."""
import hashlib
import json
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path

import pytest

import package_release as pr
from conftest import ROOT


def fake_pe(path: Path, subsystem: int) -> None:
    data = bytearray(1024)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", data, 0x80 + 24, 0x20B)
    struct.pack_into("<H", data, 0x80 + 24 + 68, subsystem)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(data))


@pytest.fixture
def prepared(tmp_path):
    src = tmp_path / "prepared"
    shutil.copytree(ROOT, src, ignore=shutil.ignore_patterns("__pycache__", ".pytest_cache"))
    if shutil.which("mcs"):  # the real starter, compiled from source
        subprocess.run(["mcs", "-langversion:5", "-nologo", "-target:winexe", "-r:System.Windows.Forms.dll",
                        "-win32icon:" + str(src / "luminary.ico"), "-out:" + str(src / "Luminary.exe"),
                        str(src / "starter" / "LuminaryStart.cs")], check=True, capture_output=True)
    else:
        fake_pe(src / "Luminary.exe", 2)
    fake_pe(src / "LuminaryLauncher.exe", 3)
    fake_pe(src / "python" / "pythonw.exe", 2)
    fake_pe(src / "python" / "python.exe", 3)
    fake_pe(src / "jre" / "bin" / "javaw.exe", 2)
    for pkg in ("PySide6", "mcstatus"):
        d = src / "python" / "Lib" / "site-packages" / pkg
        d.mkdir(parents=True)
        (d / "__init__.py").write_text("# stand-in\n")
    # per-player data that must never ship
    (src / "launcher_data").mkdir()
    (src / "launcher_data" / "session.bin").write_bytes(b"encrypted session")
    (src / "logs").mkdir()
    (src / "logs" / "launcher.log").write_text("log")
    (src / ".minecraft" / "mods").mkdir(parents=True)
    (src / ".minecraft" / "mods" / "a.jar").write_bytes(b"jar")
    (src / "nickname.txt").write_text("Someone")
    (src / "runtime").mkdir()
    return src


def test_valid_folder_packages_without_user_data_or_dev_files(prepared, tmp_path):
    assert pr.validate(prepared) == []
    r = pr.package(prepared, tmp_path / "out", "2.0.0", make_zip=True)
    dest = Path(r["folder"])
    shipped = {p.relative_to(dest).as_posix() for p in dest.rglob("*") if p.is_file()}
    for must in ("launcher.py", "Luminary.exe", "LuminaryLauncher.exe", "profiles.json", "python/pythonw.exe",
                 "luminary_app/main.py", "assets/hero_bg.png", "SHA256SUMS.txt", "release-info.json"):
        assert must in shipped
    for never in ("launcher_data/session.bin", "logs/launcher.log", ".minecraft/mods/a.jar", "nickname.txt",
                  "make_manifest.py", "package_release.py", "build_starter.bat"):
        assert never not in shipped
    assert not any(s.startswith(("tests/", "starter/")) or "__pycache__" in s for s in shipped)
    sums = dict(line.split("  ", 1)[::-1] for line in (dest / "SHA256SUMS.txt").read_text().splitlines())
    assert sums["launcher.py"] == hashlib.sha256((dest / "launcher.py").read_bytes()).hexdigest()
    with zipfile.ZipFile(r["zip"]) as z:
        assert "Luminary/Luminary.exe" in z.namelist() and z.testzip() is None


def test_zip_is_reproducible(prepared, tmp_path):
    a = pr.package(prepared, tmp_path / "a", "2.0.0", make_zip=True)["zip_sha256"]
    b = pr.package(prepared, tmp_path / "b", "2.0.0", make_zip=True)["zip_sha256"]
    assert a == b


def test_console_starter_is_refused(prepared):
    fake_pe(prepared / "Luminary.exe", 3)
    assert any("Luminary.exe" in e and "GUI" in e for e in pr.validate(prepared))


def test_missing_runtime_is_refused(prepared):
    shutil.rmtree(prepared / "jre")
    shutil.rmtree(prepared / "python" / "Lib" / "site-packages" / "PySide6")
    errors = pr.validate(prepared)
    assert any("javaw.exe" in e for e in errors) and any("PySide6" in e for e in errors)


@pytest.mark.parametrize("url,ok", [("http://localhost:8080", False), ("https://auth.example.org", True)])
def test_development_account_server_is_refused(prepared, url, ok):
    data = json.loads((prepared / "profiles.json").read_text())
    data["accounts"]["api_url"] = url
    (prepared / "profiles.json").write_text(json.dumps(data))
    assert (pr.validate(prepared) == []) == ok
    assert pr.validate(prepared, allow_dev_api=True) == []


def test_plain_http_account_server_is_refused(prepared):
    data = json.loads((prepared / "profiles.json").read_text())
    data["accounts"]["api_url"] = "http://auth.example.org"
    (prepared / "profiles.json").write_text(json.dumps(data))
    assert any("https" in e for e in pr.validate(prepared))


def test_machine_paths_and_keys_are_refused(prepared):
    (prepared / "luminary_app" / "debug_paths.py").write_text('BASE = r"C:\\Users\\shipovnik\\Desktop\\launcher"\n')
    header = "-----BEGIN RSA " + "PRIVATE KEY-----"  # assembled so the repo's secret lint is not tripped
    (prepared / "luminary_app" / "oops.py").write_text(f'KEY = """{header}"""\n')
    errors = pr.validate(prepared)
    assert any("debug_paths.py" in e for e in errors) and any("oops.py" in e for e in errors)


def test_cli_check_mode(prepared, capsys):
    assert pr.main([str(prepared), "unused", "--version", "2.0.0", "--check"]) == 0
    assert "Готово" in capsys.readouterr().out
