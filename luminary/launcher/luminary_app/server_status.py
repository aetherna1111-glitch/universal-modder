"""Live server status via the Minecraft Server List Ping (mcstatus, as in 1.x)."""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field

log = logging.getLogger("luminary.status")
_FORMAT_RE = re.compile(r"§.")


@dataclass
class ServerStatus:
    online: bool
    checked_at: float
    players: int = 0
    max_players: int = 0
    latency_ms: float | None = None
    version: str = ""
    motd: str = ""
    sample: list[str] = field(default_factory=list)
    error: str = ""


def query(host: str, port: int, timeout: float = 5.0) -> ServerStatus:
    from mcstatus import JavaServer

    try:
        st = JavaServer(host, port, timeout=timeout).status()
    except Exception as exc:  # timeouts, refused connections, malformed responses
        log.info("server %s:%s unreachable: %s", host, port, exc)
        return ServerStatus(online=False, checked_at=time.time(), error=type(exc).__name__)
    sample = []
    for p in (st.players.sample or [])[:12]:
        name = getattr(p, "name", "")
        if name and re.fullmatch(r"[A-Za-z0-9_]{1,16}", name):
            sample.append(name)
    motd = ""
    try:
        motd = st.motd.to_plain()
    except Exception:
        motd = _FORMAT_RE.sub("", str(getattr(st, "description", "")))
    return ServerStatus(online=True, checked_at=time.time(), players=st.players.online, max_players=st.players.max,
                        latency_ms=round(st.latency, 1), version=getattr(st.version, "name", ""), motd=motd.strip(),
                        sample=sample)
