"""Operator commands. Uses the same LUMINARY_* environment as the service.

  python -m luminary_auth.manage migrate
  python -m luminary_auth.manage reserve-name Steve --note "existing player"
  python -m luminary_auth.manage reserve-names-from players.txt
  python -m luminary_auth.manage unreserve-name Steve
  python -m luminary_auth.manage users
  python -m luminary_auth.manage disable Griefer / enable Griefer
  python -m luminary_auth.manage set-password Steve        (prompts; signs the user out everywhere)
  python -m luminary_auth.manage create-user Steve         (prompts; for players whose name is reserved)
  python -m luminary_auth.manage news-add "Title" "Body text" / news-list / news-delete 3
  python -m luminary_auth.manage backup /var/backups/luminary   (database + textures, safe while running)
"""
from __future__ import annotations

import argparse
import getpass
import shutil
import sys
import time
from pathlib import Path

from . import accounts, config, db, security


def _prompt_password() -> str:
    pw = getpass.getpass("New password: ")
    if pw != getpass.getpass("Repeat: "):
        sys.exit("Passwords do not match")
    return pw


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="luminary_auth.manage", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("migrate")
    p = sub.add_parser("reserve-name"); p.add_argument("name"); p.add_argument("--note", default="")
    p = sub.add_parser("reserve-names-from"); p.add_argument("file")
    p = sub.add_parser("unreserve-name"); p.add_argument("name")
    sub.add_parser("users")
    p = sub.add_parser("disable"); p.add_argument("name")
    p = sub.add_parser("enable"); p.add_argument("name")
    p = sub.add_parser("set-password"); p.add_argument("name")
    p = sub.add_parser("create-user"); p.add_argument("name")
    p = sub.add_parser("news-add"); p.add_argument("title"); p.add_argument("body")
    sub.add_parser("news-list")
    p = sub.add_parser("news-delete"); p.add_argument("id", type=int)
    p = sub.add_parser("backup"); p.add_argument("dest")
    args = ap.parse_args(argv)

    settings = config.from_env()
    conn = db.connect(settings.db_path)
    db.migrate(conn)
    t = int(time.time())

    if args.cmd == "migrate":
        print("schema up to date")
    elif args.cmd in ("reserve-name", "reserve-names-from"):
        names = [args.name] if args.cmd == "reserve-name" else \
            [ln.strip() for ln in Path(args.file).read_text(encoding="utf-8").splitlines() if ln.strip()]
        for n in names:
            security.validate_username(n)
            conn.execute("INSERT OR IGNORE INTO reserved_names(name_lower, note, created_at) VALUES (?, ?, ?)",
                         (n.lower(), getattr(args, "note", ""), t))
        print(f"reserved {len(names)} name(s)")
    elif args.cmd == "unreserve-name":
        conn.execute("DELETE FROM reserved_names WHERE name_lower = ?", (args.name.lower(),))
        print("done")
    elif args.cmd == "users":
        for r in conn.execute("SELECT u.username, u.disabled, u.created_at, p.uuid, p.skin_hash FROM users u "
                              "JOIN profiles p ON p.user_id = u.id ORDER BY u.username_lower"):
            flag = " [disabled]" if r["disabled"] else ""
            skin = "skin" if r["skin_hash"] else "-"
            print(f"{r['username']:<16} {security.dashed(r['uuid'])} {skin}{flag}")
    elif args.cmd in ("disable", "enable"):
        cur = conn.execute("UPDATE users SET disabled = ? WHERE username_lower = ?",
                           (1 if args.cmd == "disable" else 0, args.name.lower()))
        if cur.rowcount == 0:
            sys.exit("no such user")
        if args.cmd == "disable":
            conn.execute("DELETE FROM tokens WHERE user_id = (SELECT id FROM users WHERE username_lower = ?)",
                         (args.name.lower(),))
        print("done")
    elif args.cmd == "set-password":
        accounts.set_password_admin(conn, args.name, _prompt_password())
        print("password set; all sessions signed out")
    elif args.cmd == "create-user":
        # Bypasses the reservation and registration switch, for handing a reserved name to its owner.
        security.validate_username(args.name)
        pw = _prompt_password()
        conn.execute("DELETE FROM reserved_names WHERE name_lower = ?", (args.name.lower(),))
        was_open = settings.registration_open
        settings.registration_open = True
        try:
            accounts.register(conn, settings, args.name, pw, ip="manage")
        finally:
            settings.registration_open = was_open
        print("user created")
    elif args.cmd == "news-add":
        conn.execute("INSERT INTO news(title, body, published_at) VALUES (?, ?, ?)", (args.title, args.body, t))
        print("published")
    elif args.cmd == "news-list":
        for r in conn.execute("SELECT * FROM news ORDER BY published_at DESC"):
            print(f"{r['id']:>4}  {time.strftime('%Y-%m-%d', time.localtime(r['published_at']))}  {r['title']}")
    elif args.cmd == "news-delete":
        conn.execute("DELETE FROM news WHERE id = ?", (args.id,))
        print("deleted")
    elif args.cmd == "backup":
        dest = Path(args.dest) / time.strftime("luminary-%Y%m%d-%H%M%S")
        db.backup(conn, dest / "luminary.sqlite3")
        if settings.textures_dir.exists():
            shutil.copytree(settings.textures_dir, dest / "textures")
        print(f"backup written to {dest} (the signing key is NOT included; back it up separately)")
    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
