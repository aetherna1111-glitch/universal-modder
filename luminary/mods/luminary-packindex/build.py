"""Build luminary-packindex.jar (server-only Fabric mod) with plain javac, and run its tests.

  python3 build.py --libs <fabric server libraries dir> [--out build] [--test]

It uses only Fabric Loader, Gson and SLF4J (all on a Fabric 1.20.1 server's classpath); no Minecraft classes.
"""
from __future__ import annotations

import argparse
import io
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEEDED = ("fabric-loader-", "gson-", "slf4j-api-")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--libs", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=HERE / "build")
    ap.add_argument("--test", action="store_true", help="also compile and run test/ (not packaged)")
    args = ap.parse_args(argv)
    libs = [j for j in sorted(args.libs.rglob("*.jar")) if j.name.startswith(NEEDED)]
    javac = ["javac", "--release", "17", "-nowarn", "-encoding", "UTF-8", "-proc:none"]
    with tempfile.TemporaryDirectory() as tmp:
        classes = Path(tmp) / "classes"
        src = sorted(str(p) for p in (HERE / "src" / "main" / "java").rglob("*.java"))
        subprocess.run(javac + ["-cp", ":".join(map(str, libs)), "-d", str(classes)] + src, check=True)
        args.out.mkdir(parents=True, exist_ok=True)
        out = args.out / "luminary-packindex-1.0.0.jar"
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            for root in (classes, HERE / "src" / "main" / "resources"):
                for f in sorted(p for p in root.rglob("*") if p.is_file()):
                    info = zipfile.ZipInfo(f.relative_to(root).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    z.writestr(info, f.read_bytes())
        out.write_bytes(buf.getvalue())
        print(f"built {out} ({out.stat().st_size} bytes)")
        if args.test:
            tclasses = Path(tmp) / "test-classes"
            tsrc = sorted(str(p) for p in (HERE / "test" / "java").rglob("*.java"))
            cp = ":".join(map(str, libs + [classes]))
            subprocess.run(javac + ["-cp", cp, "-d", str(tclasses)] + tsrc, check=True)
            subprocess.run(["java", "-cp", f"{cp}:{tclasses}", "luminary.packindextest.PackIndexerTest"], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
