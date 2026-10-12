import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

from conftest import FIXTURES
from luminary_app import launch
from luminary_app.launch import LaunchError, LaunchRequest


def make_req(tmp_path, **kw):
    mc = tmp_path / ".minecraft"
    mc.mkdir(exist_ok=True)
    base = dict(portablemc=[sys.executable, str(FIXTURES / "fake_portablemc.py")], main_dir=mc,
                version="fabric:1.20.1", java=Path(sys.executable), ram_mb=6144, username="Steve",
                uuid=launch.java_offline_uuid("Steve"), server=("135.106.221.145", 27195))
    base.update(kw)
    return LaunchRequest(**base)


def test_offline_uuid_is_java_compatible():
    assert launch.java_offline_uuid("Notch") == "b50ad385-829d-3141-a216-7e7d7539ba7f"


def test_machine_lines_keep_windows_backslashes():
    code, args = launch.parse_machine_line("jvm_args\t-Dx=C:\\Users\\name\\new\t-cp\tC:\\a;b\n")
    assert code == "jvm_args" and args == ["-Dx=C:\\Users\\name\\new", "-cp", "C:\\a;b"]


def test_install_command_shape(tmp_path):
    req = make_req(tmp_path)
    cmd = launch.install_command(req)
    assert cmd[2:6] == ["--main-dir", str(req.main_dir), "--output", "machine"]
    assert cmd[6:8] == ["start", "--dry"]
    assert f"--jvm-arg={launch.MARKER}" in cmd and cmd[-1] == "fabric:1.20.1"
    assert cmd[cmd.index("--join-server") + 1] == "135.106.221.145"
    assert cmd[cmd.index("--join-server-port") + 1] == "27195"


def test_game_command_offline_keeps_empty_token(tmp_path):
    req = make_req(tmp_path)
    dry = ["-Dlib=C:\\x", launch.MARKER, "-cp", "a.jar", "Main", "--accessToken", "", "--userType", ""]
    cmd = launch.game_command(req, dry)
    assert cmd[0] == sys.executable
    assert cmd[1:4] == ["-Dlib=C:\\x", "-Xmx6144M", "-cp"]
    assert cmd[cmd.index("--accessToken") + 1] == ""


def test_game_command_with_account(tmp_path):
    req = make_req(tmp_path, access_token="tok123", authlib_jar=Path("/rt/authlib-injector-1.2.8.jar"),
                   yggdrasil_url="https://auth.example.org/yggdrasil", yggdrasil_prefetched=b'{"meta":{}}',
                   extra_jvm_args=["-XX:+UseG1GC"])
    dry = [launch.MARKER, "-cp", "a.jar", "Main", "--accessToken", "", "--userType", ""]
    cmd = launch.game_command(req, dry)
    i = cmd.index("-Xmx6144M")
    assert cmd[i + 1] == "-javaagent:/rt/authlib-injector-1.2.8.jar=https://auth.example.org/yggdrasil"
    assert cmd[i + 2].startswith("-Dauthlibinjector.yggdrasil.prefetched=")
    assert cmd[i + 3] == "-XX:+UseG1GC"
    assert cmd.index("-javaagent:/rt/authlib-injector-1.2.8.jar=https://auth.example.org/yggdrasil") < cmd.index("Main")
    assert cmd[cmd.index("--accessToken") + 1] == "tok123"
    assert cmd[cmd.index("--userType") + 1] == "mojang"
    assert "tok123" not in " ".join(launch._redact_cmd(cmd))


def test_missing_marker_is_an_error(tmp_path):
    with pytest.raises(LaunchError):
        launch.game_command(make_req(tmp_path), ["-cp", "a.jar", "Main"])


def test_popen_kwargs_hide_console_on_windows(monkeypatch):
    monkeypatch.setattr(launch, "IS_WINDOWS", True)
    monkeypatch.setattr(subprocess, "CREATE_NO_WINDOW", 0x08000000, raising=False)
    kw = launch.popen_kwargs(subprocess.PIPE, subprocess.STDOUT)
    assert kw["creationflags"] & 0x08000000
    assert kw["stdin"] is subprocess.DEVNULL and kw["stdout"] is subprocess.PIPE
    monkeypatch.setattr(launch, "IS_WINDOWS", False)
    assert "creationflags" not in launch.popen_kwargs(None, None)


def test_run_install_end_to_end_with_fake_portablemc(tmp_path, monkeypatch):
    args_file = tmp_path / "pmc_args.txt"
    monkeypatch.setenv("FAKE_PMC_ARGS_FILE", str(args_file))
    req = make_req(tmp_path)
    progress = []
    log_path = tmp_path / "install.log"
    res = launch.run_install(req, log_path, progress.append, threading.Event())
    assert any(p.fraction == 1.0 and "Скачивание" in p.stage for p in progress)
    assert res.jvm_version == "17.0.9"
    assert launch.MARKER in res.args and "-Djava.library.path=C:\\Users\\name\\new\\bin" in res.args
    sent = args_file.read_text(encoding="utf-8").splitlines()
    assert "--dry" in sent and "Steve" in sent
    text = log_path.read_text(encoding="utf-8")
    assert "[exit code 0]" in text and "Arguments:" in text  # stderr captured too


def test_run_install_maps_errors(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_PMC_ARGS_FILE", str(tmp_path / "a.txt"))
    monkeypatch.setenv("FAKE_PMC_MODE", "jvm_missing")
    with pytest.raises(LaunchError, match="Java не найдена") as ei:
        launch.run_install(make_req(tmp_path), tmp_path / "i.log", lambda p: None, threading.Event())
    assert ei.value.log_path == tmp_path / "i.log"


def test_run_install_cancel_terminates_child(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_PMC_ARGS_FILE", str(tmp_path / "a.txt"))
    monkeypatch.setenv("FAKE_PMC_MODE", "slow")
    cancel = threading.Event()
    threading.Timer(0.5, cancel.set).start()
    t0 = time.time()
    with pytest.raises(LaunchError, match="отменён"):
        launch.run_install(make_req(tmp_path), tmp_path / "i.log", lambda p: None, cancel)
    assert time.time() - t0 < 10


def test_missing_installer_exe(tmp_path):
    req = make_req(tmp_path, portablemc=[str(tmp_path / "LuminaryLauncher.exe")])
    with pytest.raises(LaunchError, match="LuminaryLauncher.exe"):
        launch.run_install(req, tmp_path / "i.log", lambda p: None, threading.Event())


def test_game_process_logs_and_exit_codes(tmp_path, monkeypatch):
    mc = tmp_path / ".minecraft"
    mc.mkdir()
    monkeypatch.setenv("FAKE_JAVA_ARGS_FILE", str(tmp_path / "java_args.txt"))
    cmd = [sys.executable, str(FIXTURES / "fake_java.py"), "-Xmx4096M", "Main", "--accessToken", "secret-token"]
    log_path = launch.rotate_logs(tmp_path / "logs", "game")
    gp = launch.GameProcess(cmd, mc, log_path)
    assert gp.wait() == 0
    text = log_path.read_text(encoding="utf-8")
    assert "fake java started" in text and "stderr line from game" in text
    assert "secret-token" not in text and "[game exited with code 0]" in text
    assert (tmp_path / "java_args.txt").read_text().splitlines()[-1] == "secret-token"  # but the game got it

    monkeypatch.setenv("FAKE_JAVA_EXIT", "1")
    t0 = time.time()
    gp = launch.GameProcess(cmd, mc, launch.rotate_logs(tmp_path / "logs", "game"))
    assert gp.wait() == 1
    report = launch.find_crash_report(mc, t0)
    assert report is not None and report.name.startswith("crash-")


def test_rotate_logs_keeps_last_n(tmp_path):
    d = tmp_path / "logs"
    d.mkdir()
    for i in range(15):
        (d / f"game-2026010{i:02d}.log").write_text("x")
    launch.rotate_logs(d, "game", keep=10)
    assert len(list(d.glob("game-*.log"))) == 9


def test_ensure_file_verifies_hash(tmp_path):
    import hashlib
    src = tmp_path / "src.jar"
    src.write_bytes(b"jar bytes")
    good = hashlib.sha256(b"jar bytes").hexdigest()
    dest = tmp_path / "rt" / "a.jar"
    assert launch.ensure_file(src.as_uri(), good, dest) == dest
    dest.write_bytes(b"tampered")
    assert launch.ensure_file(src.as_uri(), good, dest).read_bytes() == b"jar bytes"
    with pytest.raises(LaunchError, match="контрольная сумма"):
        launch.ensure_file(src.as_uri(), "0" * 64, tmp_path / "rt" / "b.jar")
    assert not (tmp_path / "rt" / "b.jar").exists()
