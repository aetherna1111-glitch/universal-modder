"""Host facts used to constrain settings: physical memory, Java version."""
from __future__ import annotations

import ctypes
import os
import re
import subprocess
import sys

from .paths import IS_WINDOWS

RAM_CHOICES_MB = (2048, 3072, 4096, 6144, 8192, 10240, 12288, 16384)
OS_RESERVE_MB = 1536  # leave this much for Windows and the launcher itself


def total_ram_mb() -> int | None:
    try:
        if IS_WINDOWS:
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            stat = MEMORYSTATUSEX()
            stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
                return int(stat.ullTotalPhys // (1024 * 1024))
            return None
        if sys.platform.startswith("linux"):
            with open("/proc/meminfo", encoding="ascii") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        return int(line.split()[1]) // 1024
        if sys.platform == "darwin":
            out = subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True, timeout=5)  # macOS only
            return int(out.stdout.strip()) // (1024 * 1024)
    except Exception:
        return None
    return None


def ram_choices(total_mb: int | None) -> list[int]:
    """Allocations that leave the OS enough memory; never empty (2 GB is the floor)."""
    if total_mb is None:
        return [c for c in RAM_CHOICES_MB if c <= 8192]
    limit = total_mb - OS_RESERVE_MB
    allowed = [c for c in RAM_CHOICES_MB if c <= limit]
    return allowed or [RAM_CHOICES_MB[0]]


def clamp_ram(value_mb: int, total_mb: int | None) -> int:
    choices = ram_choices(total_mb)
    if value_mb in choices:
        return value_mb
    lower = [c for c in choices if c <= value_mb]
    return lower[-1] if lower else choices[0]


def format_mb(mb: int) -> str:
    gb = mb / 1024
    return f"{gb:.0f} ГБ" if gb == int(gb) else f"{gb:.1f} ГБ"


_JAVA_VERSION_RE = re.compile(r'version "(\d+)(?:\.(\d+))?')


def java_major_version(java_path: str, timeout: float = 10) -> int | None:
    """Run `java -version` (no console window on Windows). Returns the major version or None."""
    exe = java_path
    if IS_WINDOWS and exe.lower().endswith("javaw.exe"):
        sibling = exe[:-9] + "java.exe"  # javaw prints nothing; java.exe with CREATE_NO_WINDOW is silent
        if os.path.exists(sibling):
            exe = sibling
    try:
        kwargs = {"capture_output": True, "text": True, "timeout": timeout, "stdin": subprocess.DEVNULL}
        if IS_WINDOWS:
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
        out = subprocess.run([exe, "-version"], **kwargs)
    except (OSError, subprocess.SubprocessError):
        return None
    m = _JAVA_VERSION_RE.search(out.stderr + out.stdout)
    if not m:
        return None
    major = int(m.group(1))
    return int(m.group(2) or 0) if major == 1 else major
