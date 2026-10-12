"""SQLite access: one short-lived connection per request, versioned SQL migrations."""
from __future__ import annotations

import sqlite3
from importlib import resources
from pathlib import Path

MIGRATIONS_PACKAGE = "luminary_auth.migrations"


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    # One connection per request; FastAPI may create it in a worker thread and use it in the event loop.
    conn = sqlite3.connect(str(path), timeout=10, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 10000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")
    return conn


def _migrations() -> list[tuple[int, str]]:
    out = []
    for entry in resources.files(MIGRATIONS_PACKAGE).iterdir():
        name = entry.name
        if name.endswith(".sql") and name[:4].isdigit():
            out.append((int(name[:4]), entry.read_text(encoding="utf-8")))
    return sorted(out)


def migrate(conn: sqlite3.Connection) -> int:
    """Apply pending migrations. Returns the resulting schema version."""
    conn.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
    row = conn.execute("SELECT max(version) FROM schema_version").fetchone()
    current = row[0] or 0
    for version, sql in _migrations():
        if version <= current:
            continue
        conn.execute("BEGIN IMMEDIATE")
        try:
            # executescript would COMMIT our transaction; run statements one by one instead.
            for stmt in _split_sql(sql):
                conn.execute(stmt)
            conn.execute("INSERT INTO schema_version(version) VALUES (?)", (version,))
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
        current = version
    return current


def _split_sql(sql: str) -> list[str]:
    lines = [ln for ln in sql.splitlines() if not ln.strip().startswith("--")]
    return [s.strip() for s in "\n".join(lines).split(";") if s.strip()]


def backup(conn: sqlite3.Connection, dest: Path) -> None:
    """Consistent online copy of the database (safe while the service runs)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    target = sqlite3.connect(str(dest))
    try:
        conn.backup(target)
    finally:
        target.close()
