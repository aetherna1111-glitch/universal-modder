"""Interface sounds shipped with launcher 1.x (sounds/*.wav), now actually played; can be turned off."""
from __future__ import annotations

import logging
from pathlib import Path

log = logging.getLogger("luminary.sounds")

EVENTS = {"open": "open.wav", "launch": "launch.wav", "loaded": "loaded.wav", "error": "error.wav",
          "saved": "nick_saved.wav", "ram": "ram_selected.wav"}


class Sounds:
    def __init__(self, folder: Path, enabled: bool = True):
        self.enabled = enabled
        self._effects = {}
        try:
            from PySide6.QtCore import QUrl
            from PySide6.QtMultimedia import QSoundEffect
        except Exception as exc:  # QtMultimedia missing or no audio backend
            log.info("sound disabled: %s", exc)
            return
        for name, file in EVENTS.items():
            path = folder / file
            if path.exists():
                eff = QSoundEffect()
                eff.setSource(QUrl.fromLocalFile(str(path)))
                eff.setVolume(0.45)
                self._effects[name] = eff

    def play(self, name: str) -> None:
        if not self.enabled:
            return
        eff = self._effects.get(name)
        if eff is not None:
            try:
                eff.play()
            except Exception:
                pass
