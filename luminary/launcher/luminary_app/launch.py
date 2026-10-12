"""Install with PortableMC, then start the game JVM ourselves.

Why two steps: PortableMC 5 (the bundled LuminaryLauncher.exe) can only start "offline" sessions with an
empty access token. Its `start --dry` still installs and verifies the game, loader, libraries, assets
and JVM, and prints the exact JVM command in machine mode (`jvm_args` line). We take that command, put
our JVM options where a marker argument sits, set the account's access token, and spawn Java directly.

Console windows (Windows): PortableMC is a console-subsystem exe. Started from pythonw (which has no
console) without flags, Windows opened a new console window for it on every Play. Every child process is
now created with CREATE_NO_WINDOW and its output goes to files under logs/ instead.
"""
from __future__ import annotations

import base64
import hashlib
import logging
import os
import subprocess
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

from .paths import IS_WINDOWS

log = logging.getLogger("luminary.launch")

MARKER = "-Dluminary.launch.marker=1"
GAME_LOGS_KEEP = 10


class LaunchError(Exception):
    """User-facing launch failure; `log_path` points at the file with details."""

    def __init__(self, message: str, log_path: Path | None = None):
        super().__init__(message)
        self.log_path = log_path


# --------------------------------------------------------------------------- process creation

def popen_kwargs(stdout, stderr) -> dict:
    """Arguments for every child process: no console window, no inherited stdin, output to files."""
    kw: dict = {"stdin": subprocess.DEVNULL, "stdout": stdout, "stderr": stderr, "close_fds": True}
    if IS_WINDOWS:
        kw["creationflags"] = subprocess.CREATE_NO_WINDOW
    return kw


# --------------------------------------------------------------------------- PortableMC machine output

def parse_machine_line(line: str) -> tuple[str, list[str]]:
    """Split one tab-separated machine-output line.

    PortableMC escapes only newline and tab inside values (as the two characters \\n and \\t) and does NOT
    escape backslashes, so unescaping would corrupt Windows paths such as C:\\Users\\name. Real arguments
    never contain newlines or tabs, so values are used verbatim.
    """
    parts = line.rstrip("\r\n").split("\t")
    return parts[0], parts[1:]


ERROR_MESSAGES = {
    "error_jvm_not_found": "Java не найдена. Проверь папку jre или путь к Java в настройках.",
    "error_version_not_found": "Версия Minecraft или загрузчика не найдена.",
    "error_download": "Не удалось скачать файлы игры. Проверь подключение к интернету.",
    "error_download_entry": "Не удалось скачать файлы игры. Проверь подключение к интернету.",
    "error_reqwest": "Ошибка сети при установке игры. Проверь подключение к интернету.",
    "error_io": "Ошибка записи на диск. Проверь свободное место и права на папку лаунчера.",
    "error_main_class_not_found": "Установка игры повреждена: не найден главный класс.",
    "error_client_not_found": "Установка игры повреждена: нет клиента Minecraft.",
    "error_library_not_found": "Установка игры повреждена: не хватает библиотек.",
    "error_assets_not_found": "Установка игры повреждена: нет ресурсов.",
    "error_json": "Файл версии повреждён.",
    "error_zip": "Повреждённый архив в установке игры.",
}


@dataclass
class InstallProgress:
    stage: str
    fraction: float | None = None  # 0..1 when known
    detail: str = ""


STAGES = {
    "load_version": "Проверка версии", "fetch_version": "Загрузка описания версии",
    "load_client": "Проверка клиента", "load_libraries": "Проверка библиотек",
    "assets_loading": "Проверка ресурсов", "load_logger": "Проверка логгера", "load_jvm": "Проверка Java",
    "download_resources": "Скачивание файлов игры", "resources_downloaded": "Файлы игры скачаны",
    "binaries_extracted": "Распаковка библиотек",
}


@dataclass
class DryRunResult:
    args: list[str]
    errors: list[tuple[str, list[str]]] = field(default_factory=list)
    warnings: list[tuple[str, list[str]]] = field(default_factory=list)
    jvm_version: str | None = None


def interpret_line(code: str, args: list[str], result: DryRunResult) -> InstallProgress | None:
    if code == "jvm_args":
        result.args = list(args)
        return InstallProgress("Запуск игры", 1.0)
    if code.startswith("error_"):
        result.errors.append((code, args))
        return None
    if code.startswith("warn_"):
        result.warnings.append((code, args))
        return None
    if code == "loaded_jvm" and args:
        result.jvm_version = args[1] if len(args) > 1 else args[0]
    if code == "download" and len(args) >= 2:
        try:
            done, total = (int(x) for x in args[1].split("/"))
            return InstallProgress("Скачивание файлов игры", done / total if total else None, args[0])
        except ValueError:
            return None
    if code in STAGES:
        return InstallProgress(STAGES[code])
    return None


def describe_errors(result: DryRunResult) -> str:
    for code, args in result.errors:
        if code in ERROR_MESSAGES:
            return ERROR_MESSAGES[code]
    if result.errors:
        code, args = result.errors[0]
        return f"Ошибка установки ({code}{': ' + ' '.join(args) if args else ''})"
    return "Установщик завершился с ошибкой."


# --------------------------------------------------------------------------- request

@dataclass
class LaunchRequest:
    portablemc: list[str]            # command prefix, e.g. [<base>/LuminaryLauncher.exe]
    main_dir: Path                   # .minecraft
    version: str                     # e.g. fabric:1.20.1
    java: Path
    ram_mb: int
    username: str
    uuid: str                        # dashed or plain
    access_token: str | None = None  # None = offline nickname mode (1.x behaviour)
    authlib_jar: Path | None = None
    yggdrasil_url: str | None = None
    yggdrasil_prefetched: bytes | None = None
    server: tuple[str, int] | None = None
    extra_jvm_args: list[str] = field(default_factory=list)

    @property
    def authenticated(self) -> bool:
        return bool(self.access_token)


def install_command(req: LaunchRequest) -> list[str]:
    cmd = [*req.portablemc, "--main-dir", str(req.main_dir), "--output", "machine", "start", "--dry",
           "--jvm", str(req.java), "--username", req.username, "--uuid", req.uuid, f"--jvm-arg={MARKER}"]
    if req.server:
        cmd += ["--join-server", req.server[0], "--join-server-port", str(req.server[1])]
    cmd.append(req.version)
    return cmd


def our_jvm_args(req: LaunchRequest) -> list[str]:
    args = [f"-Xmx{int(req.ram_mb)}M"]
    if req.authenticated:
        if not (req.authlib_jar and req.yggdrasil_url):
            raise LaunchError("Не настроен authlib-injector для входа через аккаунт Luminary.")
        args.append(f"-javaagent:{req.authlib_jar}={req.yggdrasil_url}")
        if req.yggdrasil_prefetched:
            args.append("-Dauthlibinjector.yggdrasil.prefetched=" +
                        base64.b64encode(req.yggdrasil_prefetched).decode("ascii"))
    args.extend(req.extra_jvm_args)
    return args


def _set_game_arg(args: list[str], name: str, value: str) -> bool:
    for i, a in enumerate(args[:-1]):
        if a == name:
            args[i + 1] = value
            return True
    return False


def game_command(req: LaunchRequest, dry_args: Iterable[str]) -> list[str]:
    args = list(dry_args)
    if MARKER not in args:
        raise LaunchError("Установщик вернул неожиданную команду запуска (нет метки аргументов).")
    i = args.index(MARKER)
    args[i:i + 1] = our_jvm_args(req)
    if req.authenticated:
        if not _set_game_arg(args, "--accessToken", req.access_token or ""):
            raise LaunchError("Версия игры не принимает токен доступа (--accessToken).")
        _set_game_arg(args, "--userType", "mojang")
    return [str(req.java), *args]


# --------------------------------------------------------------------------- authlib-injector

def ensure_file(url: str, sha256: str, dest: Path, timeout: float = 60) -> Path:
    """Download a pinned file once; verify its SHA-256 every time before use."""
    if dest.is_file() and _sha256(dest) == sha256:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    req = urllib.request.Request(url, headers={"User-Agent": "LuminaryLauncher"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r, open(tmp, "wb") as f:
            while True:
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                if f.tell() > 20 * 1024 * 1024:
                    raise LaunchError("authlib-injector: файл слишком большой")
    except LaunchError:
        tmp.unlink(missing_ok=True)
        raise
    except OSError as exc:
        tmp.unlink(missing_ok=True)
        raise LaunchError(f"Не удалось скачать authlib-injector: {getattr(exc, 'reason', exc)}") from None
    if _sha256(tmp) != sha256:
        tmp.unlink(missing_ok=True)
        raise LaunchError("authlib-injector: контрольная сумма не совпала, файл отклонён")
    os.replace(tmp, dest)
    return dest


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------- running

def rotate_logs(log_dir: Path, prefix: str, keep: int = GAME_LOGS_KEEP) -> Path:
    log_dir.mkdir(parents=True, exist_ok=True)
    old = sorted(log_dir.glob(f"{prefix}-*.log"))
    for p in old[:max(len(old) - (keep - 1), 0)]:
        try:
            p.unlink()
        except OSError:
            pass
    return log_dir / f"{prefix}-{time.strftime('%Y%m%d-%H%M%S')}.log"


def run_install(req: LaunchRequest, log_path: Path, on_progress: Callable[[InstallProgress], None],
                cancel: threading.Event) -> DryRunResult:
    cmd = install_command(req)
    if not Path(req.portablemc[-1]).exists():
        raise LaunchError(f"Не найден установщик игры: {Path(req.portablemc[-1]).name}", log_path)
    result = DryRunResult(args=[])
    log.info("install: %s", " ".join(_redact_cmd(cmd)))
    with open(log_path, "w", encoding="utf-8", errors="replace") as logf:
        logf.write("$ " + " ".join(_redact_cmd(cmd)) + "\n")
        logf.flush()
        try:
            proc = subprocess.Popen(cmd, cwd=str(req.main_dir.parent), **popen_kwargs(subprocess.PIPE, logf))
        except OSError as exc:
            raise LaunchError(f"Не удалось запустить установщик: {exc}", log_path) from None
        finished = threading.Event()
        watcher = threading.Thread(target=_kill_on_cancel, args=(proc, cancel, finished), daemon=True)
        watcher.start()
        assert proc.stdout is not None
        for raw in proc.stdout:
            line = raw.decode("utf-8", errors="replace")
            if line.startswith("jvm_args\t"):
                logf.write("jvm_args <command captured>\n")
            else:
                logf.write(line)
            code, args = parse_machine_line(line)
            p = interpret_line(code, args, result)
            if p is not None:
                on_progress(p)
        rc = proc.wait()
        finished.set()
        cancel_set = cancel.is_set()
        logf.write(f"\n[exit code {rc}]\n")
    if cancel_set:
        raise LaunchError("Запуск отменён", log_path)
    if rc != 0 or result.errors or not result.args:
        raise LaunchError(describe_errors(result), log_path)
    return result


def _kill_on_cancel(proc: subprocess.Popen, cancel: threading.Event, finished: threading.Event) -> None:
    while not finished.wait(0.2):
        if cancel.is_set():
            if proc.poll() is None:
                proc.terminate()  # our own child, by handle - never by process name
            return


def _redact_cmd(cmd: list[str]) -> list[str]:
    out, hide = [], False
    for a in cmd:
        if hide:
            out.append("***"); hide = False; continue
        if a == "--accessToken":
            hide = True
        if a.startswith("-Dauthlibinjector.yggdrasil.prefetched="):
            a = "-Dauthlibinjector.yggdrasil.prefetched=<metadata>"
        out.append(a)
    return out


class GameProcess:
    """The running game. Survives the launcher exiting (output goes straight to a file, not a pipe)."""

    def __init__(self, cmd: list[str], cwd: Path, log_path: Path):
        self.log_path = log_path
        self.started_at = time.time()
        self._logf = open(log_path, "w", encoding="utf-8", errors="replace")
        self._logf.write("$ " + " ".join(_redact_cmd(cmd)) + "\n")
        self._logf.flush()
        log.info("starting game: %s", " ".join(_redact_cmd(cmd)))
        try:
            self.proc = subprocess.Popen(cmd, cwd=str(cwd), **popen_kwargs(self._logf, subprocess.STDOUT))
        except OSError as exc:
            self._logf.close()
            raise LaunchError(f"Не удалось запустить Java: {exc}", log_path) from None

    @property
    def pid(self) -> int:
        return self.proc.pid

    def running(self) -> bool:
        return self.proc.poll() is None

    def wait(self) -> int:
        rc = self.proc.wait()
        try:
            self._logf.write(f"\n[game exited with code {rc}]\n")
            self._logf.close()
        except (OSError, ValueError):
            pass
        return rc


def find_crash_report(mc_dir: Path, since: float) -> Path | None:
    reports = mc_dir / "crash-reports"
    if not reports.is_dir():
        return None
    candidates = [p for p in reports.glob("crash-*.txt") if p.stat().st_mtime >= since - 2]
    return max(candidates, key=lambda p: p.stat().st_mtime) if candidates else None


def java_offline_uuid(name: str) -> str:
    """What an offline-mode server assigns: Java UUID.nameUUIDFromBytes("OfflinePlayer:" + name)."""
    import uuid
    digest = hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest, version=3))
