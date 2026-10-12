"""Build the one-file Windows installer: verified release folder + artwork + NSIS.

  python installer/build_installer.py <prepared launcher folder> <output dir> --version 2.0.0

<prepared> is the launcher folder with python\, jre\, LuminaryLauncher.exe and Luminary.exe (see
package_release.py). Needs makensis (NSIS 3) on PATH and Pillow. Output: Luminary-Setup-<version>.exe.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import make_art  # noqa: E402
import package_release  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--version", required=True)
    args = ap.parse_args(argv)
    makensis = shutil.which("makensis")
    if not makensis:
        print("makensis (NSIS 3) не найден")
        return 1
    work = args.out / "installer-build"
    try:
        result = package_release.package(args.source, work, args.version)
    except package_release.ReleaseError as exc:
        for line in str(exc).splitlines():
            print("ОШИБКА", line)
        return 1
    art = work / "art"
    art.mkdir(parents=True, exist_ok=True)
    sys.argv = ["make_art", str(art)]
    make_art.main()
    setup = (args.out / f"Luminary-Setup-{args.version}.exe").resolve()
    cmd = [makensis, "-V2", "-INPUTCHARSET", "UTF8", f"-DSRC={Path(result['folder']).resolve()}", f"-DART={art.resolve()}",
           f"-DVERSION={args.version}", f"-DOUTFILE={setup}", str(HERE / "luminary.nsi")]
    subprocess.run(cmd, check=True)
    digest = hashlib.sha256(setup.read_bytes()).hexdigest()
    print(f"Установщик: {setup} ({setup.stat().st_size / 1024 / 1024:.0f} МБ)\nSHA-256: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
