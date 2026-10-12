"""At-rest protection for the launcher session token.

Windows: DPAPI (CryptProtectData, current-user scope), so the file is useless on another account or PC.
Elsewhere: a file readable only by the owner. The password itself is never stored.
"""
from __future__ import annotations

import ctypes
import json
import logging
import os
from pathlib import Path

from .paths import IS_WINDOWS

log = logging.getLogger("luminary.session")
_ENTROPY = b"luminary-launcher-session-v1"


if IS_WINDOWS:
    from ctypes import wintypes

    class _BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    def _blob(data: bytes):
        # Return the buffer too: the caller must keep it alive while the BLOB is in use.
        buf = ctypes.create_string_buffer(data, len(data))
        return _BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char))), buf

    def _protect(data: bytes) -> bytes:
        out = _BLOB()
        src, _src_buf = _blob(data)
        ent, _ent_buf = _blob(_ENTROPY)
        if not ctypes.windll.crypt32.CryptProtectData(ctypes.byref(src), None, ctypes.byref(ent),
                                                      None, None, 0x1, ctypes.byref(out)):  # UI_FORBIDDEN
            raise OSError("CryptProtectData failed")
        try:
            return ctypes.string_at(out.pbData, out.cbData)
        finally:
            ctypes.windll.kernel32.LocalFree(out.pbData)

    def _unprotect(data: bytes) -> bytes:
        out = _BLOB()
        src, _src_buf = _blob(data)
        ent, _ent_buf = _blob(_ENTROPY)
        if not ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(src), None, ctypes.byref(ent),
                                                        None, None, 0x1, ctypes.byref(out)):
            raise OSError("CryptUnprotectData failed")
        try:
            return ctypes.string_at(out.pbData, out.cbData)
        finally:
            ctypes.windll.kernel32.LocalFree(out.pbData)
else:
    def _protect(data: bytes) -> bytes:
        return data

    def _unprotect(data: bytes) -> bytes:
        return data


def save_session(path: Path, session: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    blob = _protect(json.dumps(session).encode("utf-8"))
    tmp = path.with_suffix(".tmp")
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(blob)
    os.replace(tmp, path)


def load_session(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        data = json.loads(_unprotect(path.read_bytes()).decode("utf-8"))
        if isinstance(data, dict) and isinstance(data.get("access_token"), str):
            return data
    except (OSError, ValueError) as exc:
        log.warning("stored session unreadable (%s); signing out", type(exc).__name__)
    clear_session(path)
    return None


def clear_session(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass
