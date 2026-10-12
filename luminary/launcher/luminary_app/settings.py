"""User preferences: validated, saved atomically to launcher_data/settings.json.

Migrates nickname.txt / ram.txt from launcher 1.x on first run (the old files are left in place).
"""
from __future__ import annotations

import json
import logging
import os
import re
import shlex
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path

from .paths import Paths

log = logging.getLogger("luminary.settings")

NICK_RE = re.compile(r"^[A-Za-z0-9_]{3,16}$")
AFTER_LAUNCH = ("minimize", "keep", "close")
PACK_UPDATES = ("ask", "auto")
# JVM options the launcher controls itself; setting them twice breaks memory or account handling.
_RESERVED_JVM = ("-Xmx", "-Xms", "-javaagent:", "-Dauthlibinjector.")


@dataclass
class Settings:
    nickname: str = ""
    ram_mb: int = 4096
    profile_id: str = "luminary"
    java_path: str = ""
    jvm_args: str = ""
    after_launch: str = "minimize"
    pack_updates: str = "ask"
    sounds: bool = True
    reduce_motion: bool = False
    debug_logging: bool = False
    window: dict = field(default_factory=dict)

    def jvm_arg_list(self) -> list[str]:
        return shlex.split(self.jvm_args, posix=True) if self.jvm_args.strip() else []


def validate_nickname(nick: str) -> str | None:
    if not NICK_RE.fullmatch(nick or ""):
        return "Ник: 3-16 символов, только латиница, цифры и _"
    return None


def validate_jvm_args(text: str) -> str | None:
    if len(text) > 2000:
        return "Слишком длинная строка аргументов"
    try:
        args = shlex.split(text, posix=True) if text.strip() else []
    except ValueError as exc:
        return f"Не удалось разобрать аргументы: {exc}"
    for a in args:
        if not a.startswith("-"):
            return f"Аргумент JVM должен начинаться с '-': {a}"
        for r in _RESERVED_JVM:
            if a.startswith(r):
                return f"{r.rstrip(':')} задаёт сам лаунчер (память - на главной, аккаунт - автоматически)"
    return None


def validate_java_path(text: str) -> str | None:
    if not text:
        return None
    p = Path(text)
    if not p.is_absolute():
        return "Укажи полный путь к java(w).exe"
    if not p.is_file():
        return "Файл не найден"
    if p.name.lower() not in {"java.exe", "javaw.exe", "java"}:
        return "Нужен файл java.exe или javaw.exe"
    return None


def _coerce(raw: dict) -> tuple[Settings, list[str]]:
    s = Settings()
    problems = []
    known = {f.name: f for f in fields(Settings)}
    for key, value in raw.items():
        if key not in known:
            continue
        default = getattr(s, key)
        if isinstance(default, bool):
            if isinstance(value, bool):
                setattr(s, key, value)
            else:
                problems.append(key)
        elif isinstance(default, int):
            if isinstance(value, int) and not isinstance(value, bool):
                setattr(s, key, value)
            else:
                problems.append(key)
        elif isinstance(default, str):
            if isinstance(value, str):
                setattr(s, key, value)
            else:
                problems.append(key)
        elif isinstance(default, dict) and isinstance(value, dict):
            setattr(s, key, value)
    if s.nickname and validate_nickname(s.nickname):
        problems.append("nickname"); s.nickname = ""
    if not (1024 <= s.ram_mb <= 65536):
        problems.append("ram_mb"); s.ram_mb = 4096
    if s.after_launch not in AFTER_LAUNCH:
        problems.append("after_launch"); s.after_launch = "minimize"
    if s.pack_updates not in PACK_UPDATES:
        problems.append("pack_updates"); s.pack_updates = "ask"
    if validate_jvm_args(s.jvm_args):
        problems.append("jvm_args"); s.jvm_args = ""
    return s, problems


def _legacy_ram_mb(text: str) -> int | None:
    m = re.fullmatch(r"\s*(\d{1,2})\s*[Gg]\s*", text or "")
    return int(m.group(1)) * 1024 if m else None


def load(paths: Paths) -> Settings:
    if paths.settings_file.exists():
        try:
            with open(paths.settings_file, encoding="utf-8") as f:
                raw = json.load(f)
            if not isinstance(raw, dict):
                raise ValueError("not an object")
        except (OSError, ValueError) as exc:
            log.warning("settings.json unreadable (%s); using defaults", exc)
            broken = paths.settings_file.with_suffix(".broken.json")
            try:
                os.replace(paths.settings_file, broken)
            except OSError:
                pass
            return Settings()
        s, problems = _coerce(raw)
        if problems:
            log.warning("reset invalid settings: %s", ", ".join(problems))
        return s
    s = Settings()
    try:
        if paths.legacy_nick.exists():
            nick = paths.legacy_nick.read_text(encoding="utf-8").strip()
            if not validate_nickname(nick):
                s.nickname = nick
        if paths.legacy_ram.exists():
            ram = _legacy_ram_mb(paths.legacy_ram.read_text(encoding="utf-8"))
            if ram:
                s.ram_mb = ram
        if s.nickname or s.ram_mb != 4096:
            log.info("migrated nickname/ram from launcher 1.x files")
    except OSError as exc:
        log.warning("could not read legacy settings: %s", exc)
    return s


def save(paths: Paths, settings: Settings) -> None:
    paths.data_dir.mkdir(parents=True, exist_ok=True)
    tmp = paths.settings_file.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(asdict(settings), f, indent=2, ensure_ascii=False)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, paths.settings_file)
