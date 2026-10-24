#!/usr/bin/env python3
"""Report single-player assumptions in your own Far Cry scripts that matter for FarCryCoop.

Reads FCData/Scripts.pak and the level paks of a local Far Cry install (they are zip files) and lists every
script line that matches a known co-op hazard, grouped by level. Nothing is extracted or copied; the report
stays on your machine (default: farcry_coop_audit.md in the current folder).

    python3 audit_scripts.py "C:/Games/Far Cry" [-o report.md] [--level pier]

The hazards come from reading the CryENGINE MOD SDK 1.4 source (see MODLOG.md):
- `_localplayer` is set per process to that machine's own player, so on the server it is only the host;
- `Game:LoadLevel` resets g_GameType to "Default" and starts a non-listen server, which ends a co-op session;
- `Game:Save` / `Game:Load*` do nothing or desync in multiplayer;
- `Game:TouchCheckPoint` is turned into the co-op respawn point by the FarCryCoop CryGame.dll;
- `Game:IsMultiplayer()` branches change behaviour now that the campaign runs as multiplayer;
- HUD, movie and input calls inside server code only reach the host.
"""
import argparse
import fnmatch
import os
import re
import sys
import zipfile

HAZARDS = [
    ("level-transition", re.compile(r"Game:LoadLevel\s*\("),
     "ends the co-op session (forces g_GameType=Default, non-listen server); needs a co-op aware map change"),
    ("local-player", re.compile(r"\b_localplayer\b"),
     "only the host on the server; triggers/objectives keyed to it ignore other players"),
    ("save-load", re.compile(r"Game:(Save|Load|LoadLatestCheckPoint)\s*\("),
     "saving is disabled in multiplayer; loading would drop clients"),
    ("checkpoint", re.compile(r"Game:TouchCheckPoint\s*\("),
     "handled: FarCryCoop turns it into the shared respawn point"),
    ("mp-branch", re.compile(r"Game:IsMultiplayer\s*\(\s*\)"),
     "behaves differently now that the campaign runs as multiplayer; review both branches"),
    ("hud-on-server", re.compile(r"\bHud:\w+\s*\("),
     "HUD calls run on the machine executing the script; server-side calls reach only the host"),
    ("cutscene", re.compile(r"(CutScene|Movie:|PlaySequence|StopAllCutScenes)"),
     "cutscenes and sequences play on one machine and take player control there"),
    ("input", re.compile(r"Input:\w+\s*\("),
     "input bindings affect only the local machine"),
]


def scan_text(name, text, hits):
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("--"):
            continue
        for key, rx, _ in HAZARDS:
            if rx.search(line):
                hits.append((key, name, lineno, stripped[:160]))


def scan_pak(path, hits, prefix=""):
    try:
        z = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as e:
        print(f"warning: cannot open {path}: {e}", file=sys.stderr)
        return 0
    n = 0
    with z:
        for info in z.infolist():
            if not info.filename.lower().endswith(".lua") or info.file_size > 8 * 1024 * 1024:
                continue
            text = z.read(info).decode("latin-1")
            scan_text(prefix + info.filename, text, hits)
            n += 1
    return n


def scan_dir(root, rel, hits):
    n = 0
    for dirpath, _, files in os.walk(root):
        for f in files:
            full = os.path.join(dirpath, f)
            if f.lower().endswith(".lua"):
                with open(full, encoding="latin-1") as fh:
                    scan_text(os.path.join(rel, os.path.relpath(full, root)), fh.read(), hits)
                n += 1
            elif f.lower().endswith(".pak"):
                n += scan_pak(full, hits, prefix=os.path.join(rel, os.path.relpath(full, root)) + ":")
    return n


def build_report(game, level_filter):
    hits = []
    scanned = 0
    scripts_pak = os.path.join(game, "FCData", "Scripts.pak")
    if os.path.isfile(scripts_pak):
        scanned += scan_pak(scripts_pak, hits, prefix="FCData/Scripts.pak:")
    else:
        print(f"warning: {scripts_pak} not found", file=sys.stderr)
    levels = os.path.join(game, "Levels")
    level_names = sorted(d for d in os.listdir(levels) if os.path.isdir(os.path.join(levels, d))) if os.path.isdir(levels) else []
    for lvl in level_names:
        if level_filter and not fnmatch.fnmatch(lvl.lower(), level_filter.lower()):
            continue
        scanned += scan_dir(os.path.join(levels, lvl), f"Levels/{lvl}", hits)

    out = ["# FarCryCoop script audit", "", f"Game folder: `{game}`  ", f"Lua files scanned: {scanned}", ""]
    out.append("| hazard | hits | meaning |")
    out.append("|---|---|---|")
    for key, _, meaning in HAZARDS:
        out.append(f"| {key} | {sum(1 for h in hits if h[0] == key)} | {meaning} |")
    out.append("")
    groups = {}
    for key, name, lineno, line in hits:
        group = name.split(":")[0] if name.startswith("Levels/") else "FCData/Scripts.pak"
        if name.startswith("Levels/"):
            group = "/".join(name.split("/")[:2])
        groups.setdefault(group, []).append((key, name, lineno, line))
    for group in sorted(groups):
        out.append(f"## {group}")
        out.append("")
        for key, name, lineno, line in groups[group]:
            out.append(f"- `{key}` {name}:{lineno}: `{line}`")
        out.append("")
    return "\n".join(out), hits, scanned


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("game", help="Far Cry folder (contains FCData and Levels)")
    ap.add_argument("-o", "--output", default="farcry_coop_audit.md")
    ap.add_argument("--level", help="only levels matching this pattern (e.g. pier or 'f*')")
    args = ap.parse_args()
    if not os.path.isdir(args.game):
        sys.exit(f"not a folder: {args.game}")
    report, hits, scanned = build_report(args.game, args.level)
    with open(args.output, "w", encoding="utf-8") as fh:
        fh.write(report + "\n")
    print(f"{scanned} Lua files, {len(hits)} findings -> {args.output}")


if __name__ == "__main__":
    main()
