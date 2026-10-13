"""Build luminary-vsfix.jar with plain javac (no Gradle/Loom needed).

  python3 build.py --mc-jar <server-intermediary.jar> --libs <fabric server libraries dir> \
                   --vs-jar <valkyrienskies-120-2.4.11.jar> --fabric-api-jar <fabric-api-0.92.12+1.20.1.jar> \
                   --out <dir>

The mixin is written against intermediary names, so it needs no refmap. Minecraft, Valkyrien Skies and Fabric API
are compile-time classpath only; nothing from them is packaged. stubs/ holds two empty client classes that a
dedicated-server jar does not contain.
"""
from __future__ import annotations

import argparse
import io
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEEDED_LIBS = ("sponge-mixin", "fabric-loader", "netty-buffer", "netty-common", "slf4j-api", "asm-tree", "asm-9")


def find_libs(libs: Path) -> list[Path]:
    found = []
    for jar in sorted(libs.rglob("*.jar")):
        if any(jar.name.startswith(prefix) for prefix in NEEDED_LIBS):
            found.append(jar)
    return found


def nested(jar: Path, prefix: str, dest: Path) -> Path:
    with zipfile.ZipFile(jar) as z:
        name = next(n for n in z.namelist() if n.startswith("META-INF/jars/" + prefix) and n.endswith(".jar"))
        out = dest / name.rsplit("/", 1)[-1]
        out.write_bytes(z.read(name))
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mc-jar", type=Path, required=True)
    ap.add_argument("--libs", type=Path, required=True)
    ap.add_argument("--vs-jar", type=Path, required=True)
    ap.add_argument("--fabric-api-jar", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=HERE / "build")
    ap.add_argument("--src", type=Path, default=HERE / "src" / "main")
    ap.add_argument("--name", default="luminary-vsfix-1.0.0.jar")
    args = ap.parse_args(argv)

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        cp = [args.mc_jar, args.vs_jar, nested(args.fabric_api_jar, "fabric-networking-api-v1", tmp),
              nested(args.fabric_api_jar, "fabric-lifecycle-events-v1", tmp),
              nested(args.fabric_api_jar, "fabric-events-interaction-v0", tmp),
              nested(args.fabric_api_jar, "fabric-api-base", tmp), *find_libs(args.libs)]
        stubs_out = tmp / "stubs"
        classes = tmp / "classes"
        stub_src = sorted(str(p) for p in (HERE / "stubs").rglob("*.java"))
        javac = ["javac", "--release", "17", "-nowarn", "-encoding", "UTF-8"]
        subprocess.run(javac + ["-d", str(stubs_out)] + stub_src, check=True)
        sources = sorted(str(p) for p in (args.src / "java").rglob("*.java"))
        classpath = ":".join(str(p) for p in cp + [stubs_out])
        subprocess.run(javac + ["-proc:none", "-cp", classpath, "-d", str(classes)] + sources, check=True)
        if any(classes.rglob("class_310.class")) or any(classes.rglob("class_634.class")):
            sys.exit("stub classes leaked into the build")
        args.out.mkdir(parents=True, exist_ok=True)
        out = args.out / args.name
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            for root in (classes, args.src / "resources"):
                for f in sorted(p for p in root.rglob("*") if p.is_file()):
                    info = zipfile.ZipInfo(f.relative_to(root).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    z.writestr(info, f.read_bytes())
        out.write_bytes(buf.getvalue())
        print(f"built {out} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
