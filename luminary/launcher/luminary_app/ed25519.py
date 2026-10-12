"""Ed25519 signatures (RFC 8032), pure Python, standard library only.

Used to verify launcher self-updates: the portable Python shipped with the launcher has no crypto
library, and an unsigned auto-update would let anyone with write access to the update host run code on
every player's PC. This follows the RFC 8032 section 6 reference implementation; it is checked against
the RFC test vectors and the `cryptography` package in tests. Verification is not constant-time, which is
fine for checking public signatures; signing (publisher only) handles a secret key on the owner's PC.
"""
from __future__ import annotations

import hashlib
import os

_p = 2 ** 255 - 19
_L = 2 ** 252 + 27742317777372353535851937790883648493


def _inv(x: int) -> int:
    return pow(x, _p - 2, _p)


_d = -121665 * _inv(121666) % _p
_I = pow(2, (_p - 1) // 4, _p)  # sqrt(-1)


def _H(m: bytes) -> bytes:
    return hashlib.sha512(m).digest()


def _recover_x(y: int, sign: int) -> int | None:
    if y >= _p:
        return None
    x2 = (y * y - 1) * _inv(_d * y * y + 1) % _p
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_p + 3) // 8, _p)
    if (x * x - x2) % _p != 0:
        x = x * _I % _p
    if (x * x - x2) % _p != 0:
        return None
    if (x & 1) != sign:
        x = _p - x
    return x


_Gy = 4 * _inv(5) % _p
_Gx = _recover_x(_Gy, 0)
_G = (_Gx, _Gy, 1, _Gx * _Gy % _p)
_ZERO = (0, 1, 1, 0)


def _add(P, Q):
    A = (P[1] - P[0]) * (Q[1] - Q[0]) % _p
    B = (P[1] + P[0]) * (Q[1] + Q[0]) % _p
    C = 2 * P[3] * Q[3] * _d % _p
    D = 2 * P[2] * Q[2] % _p
    E, F, G, H = B - A, D - C, D + C, B + A
    return (E * F % _p, G * H % _p, F * G % _p, E * H % _p)


def _mul(s: int, P):
    Q = _ZERO
    while s > 0:
        if s & 1:
            Q = _add(Q, P)
        P = _add(P, P)
        s >>= 1
    return Q


def _equal(P, Q) -> bool:
    return (P[0] * Q[2] - Q[0] * P[2]) % _p == 0 and (P[1] * Q[2] - Q[1] * P[2]) % _p == 0


def _compress(P) -> bytes:
    zinv = _inv(P[2])
    x, y = P[0] * zinv % _p, P[1] * zinv % _p
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _decompress(s: bytes):
    if len(s) != 32:
        return None
    y = int.from_bytes(s, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _p)


def _expand(secret: bytes) -> tuple[int, bytes]:
    if len(secret) != 32:
        raise ValueError("Ed25519 secret key must be 32 bytes")
    h = _H(secret)
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def _hint(m: bytes) -> int:
    return int.from_bytes(_H(m), "little") % _L


def generate_secret() -> bytes:
    return os.urandom(32)


def public_key(secret: bytes) -> bytes:
    a, _ = _expand(secret)
    return _compress(_mul(a, _G))


def sign(secret: bytes, message: bytes) -> bytes:
    a, prefix = _expand(secret)
    A = _compress(_mul(a, _G))
    r = _hint(prefix + message)
    Rs = _compress(_mul(r, _G))
    s = (r + _hint(Rs + A + message) * a) % _L
    return Rs + int.to_bytes(s, 32, "little")


def verify(public: bytes, message: bytes, signature: bytes) -> bool:
    if len(public) != 32 or len(signature) != 64:
        return False
    A = _decompress(public)
    if A is None:
        return False
    Rs = signature[:32]
    R = _decompress(Rs)
    if R is None:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _L:
        return False
    h = _hint(Rs + public + message)
    return _equal(_mul(s, _G), _add(R, _mul(h, A)))
