"""RSA key used to sign profile properties (textures), as Minecraft and authlib-injector expect."""
from __future__ import annotations

import base64
import os
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


class Signer:
    def __init__(self, private_key: rsa.RSAPrivateKey):
        self._key = private_key

    @classmethod
    def load_or_create(cls, path: Path) -> "Signer":
        if path.exists():
            data = path.read_bytes()
            key = serialization.load_pem_private_key(data, password=None)
            if not isinstance(key, rsa.RSAPrivateKey):
                raise ValueError(f"{path} is not an RSA private key")
            return cls(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
        pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        # Write to a private temp file (0600 from the start), then link it into place atomically.
        # If another worker created the key meanwhile, use theirs.
        tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(pem)
            f.flush()
            os.fsync(f.fileno())
        try:
            os.link(tmp, path)
        except FileExistsError:
            os.unlink(tmp)
            return cls.load_or_create(path)
        os.unlink(tmp)
        return cls(key)

    def public_pem(self) -> str:
        return self._key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode("ascii")

    def sign(self, value: str) -> str:
        """SHA1withRSA over the property value (the algorithm authlib verifies)."""
        sig = self._key.sign(value.encode("utf-8"), padding.PKCS1v15(), hashes.SHA1())
        return base64.b64encode(sig).decode("ascii")

    def verify(self, value: str, signature_b64: str) -> bool:
        try:
            self._key.public_key().verify(
                base64.b64decode(signature_b64), value.encode("utf-8"), padding.PKCS1v15(), hashes.SHA1()
            )
            return True
        except Exception:
            return False
