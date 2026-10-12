"""End-to-end check that a Minecraft server verifies Luminary accounts and delivers skins to other players.

It logs two real accounts into the server with the Minecraft 1.20.1 protocol (encryption, session join via
the Luminary Yggdrasil API, compression), then checks that:
  1. each client is admitted only after the server verified the join (online-mode + authlib-injector);
  2. the second client receives the first player's *signed* textures property (the skin) from the server,
     the signature verifies against the accounts service key, and the PNG it points to downloads;
  3. a client that claims a name without a valid session is refused.

Usage (against your deployment):
  python tools/verify_skins.py --api https://auth.example.org --server mc.example.org:25565 \
      --a Alice:alicepassword --b Bob:bobpassword
Requires: cryptography. Exit code 0 = all checks passed.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import socket
import struct
import sys
import time
import urllib.request
import uuid
import zlib

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms

try:  # cryptography >= 43 keeps CFB8 (used by Minecraft) in the "decrepit" namespace
    from cryptography.hazmat.decrepit.ciphers.modes import CFB8
except ImportError:  # pragma: no cover
    from cryptography.hazmat.primitives.ciphers.modes import CFB8

PROTOCOL_1_20_1 = 763
PLAYER_INFO_UPDATE = 0x3A   # clientbound, 1.20/1.20.1
KEEP_ALIVE_CB, KEEP_ALIVE_SB = 0x23, 0x12


# ---------------------------------------------------------------- wire helpers
def varint(n: int) -> bytes:
    n &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def mc_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return varint(len(b)) + b


class Reader:
    def __init__(self, data: bytes):
        self.b, self.i = data, 0

    def take(self, n: int) -> bytes:
        v = self.b[self.i:self.i + n]
        if len(v) != n:
            raise EOFError("truncated packet")
        self.i += n
        return v

    def varint(self) -> int:
        num = shift = 0
        while True:
            byte = self.take(1)[0]
            num |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return num - (1 << 32) if num & (1 << 31) else num
            shift += 7

    def string(self) -> str:
        return self.take(self.varint()).decode("utf-8")

    def bool(self) -> bool:
        return self.take(1) != b"\x00"

    def uuid(self) -> str:
        return uuid.UUID(bytes=self.take(16)).hex


class Conn:
    def __init__(self, host: str, port: int):
        self.sock = socket.create_connection((host, port), timeout=15)
        self.enc = self.dec = None
        self.threshold = -1
        self.buf = b""

    def _recv_raw(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("server closed the connection")
            self.buf += self.dec.update(chunk) if self.dec else chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _read_varint(self) -> int:
        num = shift = 0
        while True:
            byte = self._recv_raw(1)[0]
            num |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return num
            shift += 7

    def send(self, packet_id: int, payload: bytes = b"") -> None:
        data = varint(packet_id) + payload
        if self.threshold >= 0:
            if len(data) >= self.threshold:
                data = varint(len(data)) + zlib.compress(data)
            else:
                data = varint(0) + data
        frame = varint(len(data)) + data
        self.sock.sendall(self.enc.update(frame) if self.enc else frame)

    def recv(self) -> tuple[int, Reader]:
        length = self._read_varint()
        data = self._recv_raw(length)
        if self.threshold >= 0:
            r = Reader(data)
            dlen = r.varint()
            data = zlib.decompress(data[r.i:]) if dlen else data[r.i:]
        r = Reader(data)
        return r.varint(), r

    def enable_encryption(self, secret: bytes) -> None:
        self.enc = Cipher(algorithms.AES(secret), CFB8(secret)).encryptor()
        self.dec = Cipher(algorithms.AES(secret), CFB8(secret)).decryptor()
        self.buf = self.dec.update(self.buf) if self.buf else b""


def server_hash(server_id: str, secret: bytes, pubkey: bytes) -> str:
    digest = hashlib.sha1(server_id.encode("latin-1") + secret + pubkey).digest()
    n = int.from_bytes(digest, "big", signed=True)
    return format(n, "x") if n >= 0 else "-" + format(-n, "x")


# ---------------------------------------------------------------- accounts API
def http_json(url: str, body: dict | None = None, token: str | None = None) -> tuple[int, dict | None]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET",
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, None


def game_token(api: str, name: str, password: str) -> dict:
    st, sess = http_json(f"{api}/api/v1/auth/login", {"username": name, "password": password})
    if st != 200:
        raise SystemExit(f"login failed for {name}: HTTP {st}")
    st, tok = http_json(f"{api}/api/v1/game/token", {}, sess["access_token"])
    if st != 200:
        raise SystemExit(f"game token failed for {name}: HTTP {st}")
    return tok


# ---------------------------------------------------------------- one client
class Client:
    def __init__(self, host, port, ygg, name, token=None, profile_uuid=None, join=True):
        self.host, self.port, self.ygg, self.name = host, port, ygg, name
        self.token, self.profile_uuid, self.join = token, profile_uuid, join
        self.props: list[dict] = []
        self.seen_players: dict[str, dict] = {}
        self.disconnect_reason = None

    def login(self) -> bool:
        c = self.conn = Conn(self.host, self.port)
        c.send(0x00, varint(PROTOCOL_1_20_1) + mc_string(self.host) + struct.pack(">H", self.port) + varint(2))
        c.send(0x00, mc_string(self.name) + b"\x01" + uuid.UUID(self.profile_uuid or uuid.uuid4().hex).bytes)
        while True:
            pid, r = c.recv()
            if pid == 0x00:
                self.disconnect_reason = r.string()
                return False
            if pid == 0x01:
                server_id = r.string()
                pubkey = r.take(r.varint())
                verify = r.take(r.varint())
                secret = os.urandom(16)
                if self.join:
                    st, _ = http_json(f"{self.ygg}/sessionserver/session/minecraft/join", {
                        "accessToken": self.token or "forged", "selectedProfile": self.profile_uuid or "",
                        "serverId": server_hash(server_id, secret, pubkey)})
                    self.join_status = st
                key = serialization.load_der_public_key(pubkey)
                c.send(0x01, varint(128) + key.encrypt(secret, padding.PKCS1v15()) +
                       varint(128) + key.encrypt(verify, padding.PKCS1v15()))
                c.enable_encryption(secret)
            elif pid == 0x03:
                c.threshold = r.varint()
            elif pid == 0x02:
                self.uuid = r.uuid()
                self.final_name = r.string()
                for _ in range(r.varint()):
                    p = {"name": r.string(), "value": r.string()}
                    if r.bool():
                        p["signature"] = r.string()
                    self.props.append(p)
                return True
            elif pid == 0x04:  # login plugin request -> "not understood"
                msg_id = r.varint()
                c.send(0x02, varint(msg_id) + b"\x00")

    def pump(self, seconds: float) -> None:
        end = time.time() + seconds
        self.conn.sock.settimeout(1.0)
        while time.time() < end:
            try:
                pid, r = self.conn.recv()
            except socket.timeout:
                continue
            if pid == KEEP_ALIVE_CB:
                self.conn.send(KEEP_ALIVE_SB, r.take(8))
            elif pid == PLAYER_INFO_UPDATE:
                self._player_info(r)

    def _player_info(self, r: Reader) -> None:
        """Player Info Update (1.19.3-1.20.1): action bitset, then per player the data of each action."""
        actions = r.take(1)[0]
        for _ in range(r.varint()):
            pu = r.uuid()
            if actions & 0x01:  # add player: name + properties
                name = r.string()
                props = []
                for _ in range(r.varint()):
                    p = {"name": r.string(), "value": r.string()}
                    if r.bool():
                        p["signature"] = r.string()
                    props.append(p)
                self.seen_players[pu] = {"name": name, "properties": props}
            if actions & 0x02 and r.bool():  # initialize chat: session id, expiry, key, signature
                r.take(16 + 8)
                r.take(r.varint())
                r.take(r.varint())
            if actions & 0x04:
                r.varint()  # game mode
            if actions & 0x08:
                r.bool()    # listed
            if actions & 0x10:
                r.varint()  # latency
            if actions & 0x20 and r.bool():
                r.string()  # display name (JSON text in 1.20.1)

    def close(self) -> None:
        try:
            self.conn.sock.close()
        except Exception:
            pass


def textures(props: list[dict]) -> dict | None:
    for p in props:
        if p["name"] == "textures":
            return p
    return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", required=True, help="accounts service base URL")
    ap.add_argument("--server", required=True, help="host:port of the Minecraft server")
    ap.add_argument("--a", required=True, help="Name:password of an account WITH a skin")
    ap.add_argument("--b", required=True, help="Name:password of a second account")
    args = ap.parse_args(argv)
    host, port = args.server.rsplit(":", 1)
    port = int(port)
    api = args.api.rstrip("/")
    ygg = api + "/yggdrasil"
    meta = json.loads(urllib.request.urlopen(ygg + "/", timeout=15).read())
    pub = serialization.load_pem_public_key(meta["signaturePublickey"].encode())
    (na, pa), (nb, pb) = args.a.split(":", 1), args.b.split(":", 1)
    ta, tb = game_token(api, na, pa), game_token(api, nb, pb)
    failures = []

    def check(ok: bool, text: str):
        print(("PASS " if ok else "FAIL ") + text)
        if not ok:
            failures.append(text)

    a = Client(host, port, ygg, ta["name"], ta["access_token"], ta["uuid"])
    check(a.login(), f"{ta['name']} admitted after verified join (reason: {a.disconnect_reason})")
    b = Client(host, port, ygg, tb["name"], tb["access_token"], tb["uuid"])
    check(b.login(), f"{tb['name']} admitted after verified join (reason: {b.disconnect_reason})")
    if not failures:
        check(a.uuid == ta["uuid"], f"server assigned the account UUID to {ta['name']} ({a.uuid})")
        a.pump(1.0)
        b.pump(4.0)
        seen = b.seen_players.get(ta["uuid"])
        check(seen is not None, f"{tb['name']} sees {ta['name']} in the player list")
        tex = textures(seen["properties"]) if seen else None
        check(tex is not None and "signature" in tex, f"{tb['name']} received {ta['name']}'s signed textures")
        if tex and "signature" in tex:
            try:
                pub.verify(base64.b64decode(tex["signature"]), tex["value"].encode(), padding.PKCS1v15(), hashes.SHA1())
                sig_ok = True
            except Exception:
                sig_ok = False
            check(sig_ok, "textures signature verifies with the accounts service public key")
            payload = json.loads(base64.b64decode(tex["value"]))
            skin = payload.get("textures", {}).get("SKIN")
            check(payload.get("profileName") == ta["name"] and skin is not None,
                  f"textures name the right player and carry a SKIN url ({skin and skin['url']})")
            if skin:
                png = urllib.request.urlopen(skin["url"], timeout=15).read()
                check(png.startswith(b"\x89PNG"), f"skin PNG downloads ({len(png)} bytes)")
                print(f"     model: {skin.get('metadata', {}).get('model', 'classic')}")
    a.close()
    b.close()

    # impersonation attempts
    imp = Client(host, port, ygg, ta["name"], tb["access_token"], ta["uuid"])  # B's token, A's identity
    check(not imp.login(), f"claiming {ta['name']} with someone else's token is refused ({imp.disconnect_reason})")
    imp.close()
    off = Client(host, port, ygg, ta["name"], None, ta["uuid"], join=False)    # offline-style client
    check(not off.login(), f"claiming {ta['name']} without a session is refused ({off.disconnect_reason})")
    off.close()
    print("ALL CHECKS PASSED" if not failures else f"{len(failures)} CHECK(S) FAILED")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
