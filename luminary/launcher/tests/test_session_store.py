"""Session file: always written through the platform protector, never as plaintext fallback.

DPAPI itself can only be exercised on Windows (see docs/TESTING.md); here the protector is replaced by a
reversible stand-in to check the plumbing around it."""
import pytest

from luminary_app import secure_store

TOKEN = "ab" * 32


def test_file_holds_only_protected_bytes(tmp_path, monkeypatch):
    monkeypatch.setattr(secure_store, "_protect", lambda b: b"DPAPI:" + bytes(x ^ 0x5A for x in b))
    monkeypatch.setattr(secure_store, "_unprotect",
                        lambda b: bytes(x ^ 0x5A for x in b[6:]) if b.startswith(b"DPAPI:") else b"")
    path = tmp_path / "session.bin"
    secure_store.save_session(path, {"access_token": TOKEN, "username": "Diver"})
    raw = path.read_bytes()
    assert raw.startswith(b"DPAPI:") and TOKEN.encode() not in raw
    assert secure_store.load_session(path)["access_token"] == TOKEN


def test_protector_failure_writes_nothing(tmp_path, monkeypatch):
    def fail(_b):
        raise OSError("CryptProtectData failed")
    monkeypatch.setattr(secure_store, "_protect", fail)
    path = tmp_path / "session.bin"
    with pytest.raises(OSError):
        secure_store.save_session(path, {"access_token": TOKEN})
    assert not path.exists() and not list(tmp_path.iterdir())


def test_unreadable_file_signs_out_and_is_removed(tmp_path, monkeypatch):
    def fail(_b):
        raise OSError("CryptUnprotectData failed")  # e.g. file copied from another Windows user
    path = tmp_path / "session.bin"
    path.write_bytes(b"foreign blob")
    monkeypatch.setattr(secure_store, "_unprotect", fail)
    assert secure_store.load_session(path) is None and not path.exists()
