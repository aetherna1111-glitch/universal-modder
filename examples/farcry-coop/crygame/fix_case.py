"""Add symlinks for #include names that only differ in case from the file on disk.

The SDK sources were written on Windows (case-insensitive). Usage: fix_case.py <src-root> <xwin-root>
"""
import os
import re
import sys

INC = re.compile(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]', re.M)


def index_dirs(dirs):
    lower = {}
    for d in dirs:
        for root, _, files in os.walk(d):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, d)
                lower.setdefault((d, rel.lower()), full)
    return lower


def main():
    src, xwin = sys.argv[1], sys.argv[2]
    search = [os.path.join(src, p) for p in ("CryGame", "CryCommon", "STLPORT/stlport")]
    search += [os.path.join(xwin, p) for p in ("crt/include", "sdk/include/ucrt", "sdk/include/um",
                                               "sdk/include/shared")]
    search = [d for d in search if os.path.isdir(d)]
    idx = index_dirs(search)
    made = 0
    for d in search[:2]:
        for root, _, files in os.walk(d):
            for f in files:
                if not f.lower().endswith((".h", ".cpp", ".inl", ".c")):
                    continue
                path = os.path.join(root, f)
                text = open(path, encoding="latin-1").read()
                # Windows-style include paths ("Util\\GuidUtil.h", ".\\game.h") -> forward slashes.
                fixed = re.sub(r'(#\s*include\s*[<"])([^>"]+)', lambda m: m.group(1) + m.group(2).replace("\\", "/").removeprefix("./"), text)
                if fixed != text:
                    open(path, "w", encoding="latin-1", newline="").write(fixed)
                    text = fixed
                for name in INC.findall(text):
                    name = name.replace("\\", "/")
                    cands = [root] + search
                    if any(os.path.exists(os.path.join(c, name)) for c in cands):
                        continue
                    for c in cands:
                        hit = idx.get((c, name.lower())) or (
                            os.path.join(c, next((x for x in os.listdir(c) if x.lower() == name.lower()), ""))
                            if "/" not in name and os.path.isdir(c) else None)
                        if hit and os.path.isfile(hit):
                            link = os.path.join(c, name)
                            os.makedirs(os.path.dirname(link), exist_ok=True)
                            if not os.path.lexists(link):
                                os.symlink(os.path.relpath(hit, os.path.dirname(link)), link)
                                made += 1
                            break
    print(f"fix_case: {made} case links")


if __name__ == "__main__":
    main()
