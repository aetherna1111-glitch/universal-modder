import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import verify_skins  # noqa: E402


def test_server_hash_matches_minecraft_test_vectors():
    # wiki.vg "Protocol Encryption": sha1 hex digests printed the way Java's BigInteger does
    import hashlib

    def mc_hex(s):
        n = int.from_bytes(hashlib.sha1(s.encode()).digest(), "big", signed=True)
        return format(n, "x") if n >= 0 else "-" + format(-n, "x")

    assert mc_hex("Notch") == "4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"
    assert mc_hex("jeb_") == "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1"
    assert mc_hex("simon") == "88e16a1019277b15d58faf0541e11910eb756f6"
    # the tool's version (serverId + secret + key) agrees with the same rule
    assert verify_skins.server_hash("Notch", b"", b"") == "4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"
    assert verify_skins.server_hash("jeb_", b"", b"") == "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1"


def test_varint_roundtrip():
    for n in (0, 1, 127, 128, 255, 25565, 2097151, 2147483647, -1):
        r = verify_skins.Reader(verify_skins.varint(n))
        assert r.varint() == n
