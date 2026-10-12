"""Filesystem layout. Everything stays inside the portable launcher folder, as before."""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"


@dataclass(frozen=True)
class Paths:
    base: Path

    @property
    def mc_dir(self) -> Path:
        return self.base / ".minecraft"

    @property
    def data_dir(self) -> Path:
        return self.base / "launcher_data"

    @property
    def settings_file(self) -> Path:
        return self.data_dir / "settings.json"

    @property
    def session_file(self) -> Path:
        return self.data_dir / "session.bin"

    @property
    def log_dir(self) -> Path:
        return self.base / "logs"

    @property
    def runtime_dir(self) -> Path:
        return self.base / "runtime"

    @property
    def assets_dir(self) -> Path:
        return self.base / "assets"

    @property
    def sounds_dir(self) -> Path:
        return self.base / "sounds"

    @property
    def icon(self) -> Path:
        return self.base / "luminary.ico"

    @property
    def profiles_file(self) -> Path:
        return self.base / "profiles.json"

    @property
    def portablemc(self) -> Path:
        # The bundled PortableMC 5 CLI, renamed by the owner.
        return self.base / ("LuminaryLauncher.exe" if IS_WINDOWS else "portablemc")

    # legacy single-value files written by launcher 1.x
    @property
    def legacy_nick(self) -> Path:
        return self.base / "nickname.txt"

    @property
    def legacy_ram(self) -> Path:
        return self.base / "ram.txt"

    def ensure(self) -> None:
        for d in (self.data_dir, self.log_dir, self.runtime_dir, self.mc_dir):
            d.mkdir(parents=True, exist_ok=True)


def default_base() -> Path:
    env = os.environ.get("LUMINARY_HOME")
    if env:
        return Path(env).resolve()
    return Path(__file__).resolve().parent.parent
