"""What the launcher can honestly say about "ready to launch" (1.x always printed it, hard-coded)."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .modpack import local_version
from .paths import Paths
from .profiles import Profile


@dataclass
class Readiness:
    java: bool
    installer: bool
    installed: bool
    pack_version: str | None

    @property
    def can_launch(self) -> bool:
        return self.java and self.installer

    def summary(self) -> tuple[str, str]:
        """(text, level) where level is ok | warning | error."""
        if not self.java:
            return "Нет Java: проверь папку jre", "error"
        if not self.installer:
            return "Нет установщика игры (LuminaryLauncher.exe)", "error"
        if not self.installed:
            return "Игра будет скачана при первом запуске", "warning"
        return "Готов к запуску", "ok"


def installed_version_dirs(mc_dir: Path, profile: Profile) -> list[Path]:
    versions = mc_dir / "versions"
    if not versions.is_dir():
        return []
    if profile.loader in ("fabric", "quilt"):
        prefix = f"{profile.loader}-{profile.minecraft}-"
        if profile.loader_version:
            return [d for d in versions.iterdir() if d.name == prefix + profile.loader_version]
        return [d for d in versions.iterdir() if d.is_dir() and d.name.startswith(prefix)]
    if profile.loader == "vanilla":
        return [d for d in versions.iterdir() if d.name == profile.minecraft]
    return [d for d in versions.iterdir() if d.is_dir() and profile.minecraft in d.name and profile.loader in d.name]


def check(paths: Paths, profile: Profile, java_path: Path) -> Readiness:
    return Readiness(java=java_path.is_file(), installer=paths.portablemc.is_file(),
                     installed=bool(installed_version_dirs(paths.mc_dir, profile)),
                     pack_version=local_version(paths.mc_dir))
