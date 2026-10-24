#!/usr/bin/env bash
# Builds FarCryCoop.exe (32-bit Windows GUI app with a command-line mode) with mingw-w64.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/../dist/build/FarCryCoop.exe}"
CC="${CC:-i686-w64-mingw32-gcc}"
WINDRES="${WINDRES:-i686-w64-mingw32-windres}"
mkdir -p "$(dirname "$OUT")" "$HERE/build"
"$WINDRES" "$HERE/launcher.rc" -O coff -o "$HERE/build/launcher.res"
"$CC" -std=gnu99 -O2 -Wall -Wextra -Wno-unused-parameter -Werror -mwindows -static -s \
  -o "$OUT" "$HERE"/main.c "$HERE"/game.c "$HERE"/util.c "$HERE"/lobby_net.c "$HERE"/lobby_proto.c "$HERE/build/launcher.res" \
  -lws2_32 -lversion -lshell32 -lole32
echo "built $OUT"
