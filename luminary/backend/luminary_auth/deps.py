"""Shared request helpers: settings, DB connection, client IP, bearer auth."""
from __future__ import annotations

import sqlite3
from typing import Iterator

from fastapi import Depends, Request

from . import accounts, db
from .accounts import TokenInfo
from .config import Settings


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_conn(request: Request) -> Iterator[sqlite3.Connection]:
    conn = db.connect(request.app.state.settings.db_path)
    try:
        yield conn
    finally:
        conn.close()


def client_ip(request: Request) -> str:
    settings: Settings = request.app.state.settings
    if settings.trust_proxy:
        fwd = request.headers.get("x-forwarded-for", "")
        if fwd:
            # The reverse proxy appends the address it saw; the last entry is the one we can trust.
            return fwd.split(",")[-1].strip()[:64]
    return (request.client.host if request.client else "unknown")[:64]


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    if header[:7].lower() == "bearer ":
        return header[7:].strip()
    return None


def current_session(request: Request, conn: sqlite3.Connection = Depends(get_conn)) -> TokenInfo:
    return accounts.resolve_token(conn, bearer_token(request), "session")
