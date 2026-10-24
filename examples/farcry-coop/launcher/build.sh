#!/usr/bin/env bash
# Builds FarCryCoop.exe (32-bit Windows GUI app with a command-line mode) with mingw-w64.
#
# The mod files are embedded in the exe (RCDATA resources + manifest), so FarCryCoop.exe alone can install the
# mod. Staging: ../mod/FarCryCoop + the co-op CryGame.dll + the CryENGINE MOD SDK EULA. Set EMBED=0 to build a
# launcher without embedded files (it then needs mod\FarCryCoop next to it).
#   DLL       co-op CryGame.dll (default ../dist/build/coop/CryGame.dll)
#   SDK_EULA  EULA.txt of the SDK checkout (default ~/.cache/farcry-coop/sdk-ef7b86c/EULA.txt)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/../dist/build/FarCryCoop.exe}"
CC="${CC:-i686-w64-mingw32-gcc}"
WINDRES="${WINDRES:-i686-w64-mingw32-windres}"
DLL="${DLL:-$HERE/../dist/build/coop/CryGame.dll}"
SDK_EULA="${SDK_EULA:-${FC_CACHE:-$HOME/.cache/farcry-coop}/sdk-ef7b86c/EULA.txt}"
mkdir -p "$(dirname "$OUT")" "$HERE/build"

RES=("$HERE/build/launcher.res")
"$WINDRES" "$HERE/launcher.rc" -O coff -o "$HERE/build/launcher.res"

if [ "${EMBED:-1}" = 1 ]; then
  for f in "$DLL" "$SDK_EULA"; do [ -f "$f" ] || { echo "missing $f (build the DLL first, or EMBED=0)" >&2; exit 1; }; done
  STAGE="$HERE/build/stage/FarCryCoop"
  rm -rf "$HERE/build/stage"; mkdir -p "$STAGE/Bin32"
  cp -r "$HERE/../mod/FarCryCoop/." "$STAGE/"
  cp "$DLL" "$STAGE/Bin32/CryGame.dll"
  cp "$SDK_EULA" "$STAGE/EULA.txt"
  python3 - "$STAGE" "$HERE/build/embed.rc" "$HERE/build/manifest.txt" <<'PY'
import hashlib, os, sys
stage, rc, manifest = sys.argv[1:4]
files = sorted(os.path.relpath(os.path.join(d, f), stage) for d, _, fs in os.walk(stage) for f in fs)
lines, rcl = [], ['#include <winver.h>']
for i, rel in enumerate(files):
    rid = 100 + i
    full = os.path.join(stage, rel)
    digest = hashlib.sha256(open(full, 'rb').read()).hexdigest()
    lines.append(f"# sha256 {digest} {rel}")
    lines.append(f"{rid}|{rel.replace('/', chr(92))}")
    rcl.append(f'{rid} RCDATA "{full}"')
open(manifest, 'w', newline='\n').write("\n".join(lines) + "\n")
rcl.append(f'99 RCDATA "{manifest}"')
open(rc, 'w').write("\n".join(rcl) + "\n")
print(f"embedding {len(files)} mod files")
PY
  "$WINDRES" "$HERE/build/embed.rc" -O coff -o "$HERE/build/embed.res"
  RES+=("$HERE/build/embed.res")
fi

"$CC" -std=gnu99 -O2 -Wall -Wextra -Wno-unused-parameter -Werror -mwindows -static -s \
  -o "$OUT" "$HERE"/main.c "$HERE"/game.c "$HERE"/util.c "$HERE"/lobby_net.c "$HERE"/lobby_proto.c "${RES[@]}" \
  -lws2_32 -lversion -lshell32 -lole32
echo "built $OUT"
