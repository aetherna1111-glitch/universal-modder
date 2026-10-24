#!/usr/bin/env bash
# Runs every FarCryCoop test that does not need the real game:
#   1. CoopArgs launch-argument parser (native)
#   2. LAN lobby protocol, with ASan/UBSan fuzzing (native) and the same tests built for Windows under wine
#   3. Scripts/Coop/GameRules.lua under Lua 5.0.3 with mocked engine objects (Lua is fetched once, checksummed)
#   4. tools/audit_scripts.py (pytest)
#   5. CryGame.dll (stock + co-op) load test under wine with a stub CrySystem.dll
#   6. FarCryCoop.exe under wine against a fake Far Cry folder, incl. LAN discovery between two launchers
# Build first: crygame/build.sh, STOCK=1 crygame/build.sh, launcher/build.sh.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
TMP="$(mktemp -d)"
export WINEPREFIX="${WINEPREFIX:-$HOME/.wine-fc}" WINEDEBUG=-all WINEARCH=win32
CACHE="${FC_CACHE:-$HOME/.cache/farcry-coop}"
status=0
step() { echo; echo "== $1"; }
res() { if [ "$1" = 0 ]; then echo "-> pass"; else echo "-> FAIL"; status=1; fi; }

step "CoopArgs parser"
g++ -Wall -Wextra -I"$ROOT/crygame/coop" "$HERE/test_coopargs.cpp" "$ROOT/crygame/coop/CoopArgs.cpp" -o "$TMP/args" && "$TMP/args"; res $?

step "lobby protocol (native, ASan/UBSan + fuzz)"
gcc -Wall -Wextra -fsanitize=address,undefined -g -I"$ROOT/launcher" "$HERE/test_lobby_proto.c" "$ROOT/launcher/lobby_proto.c" -o "$TMP/lobby" && "$TMP/lobby"; res $?

step "lobby protocol (Windows build under wine)"
i686-w64-mingw32-gcc -O2 -I"$ROOT/launcher" "$HERE/test_lobby_proto.c" "$ROOT/launcher/lobby_proto.c" -o "$TMP/lobby.exe" && wine "$TMP/lobby.exe"; res $?

step "GameRules.lua under Lua 5.0.3"
LUA="$CACHE/lua50/lua-5.0.3/bin/lua"
if [ ! -x "$LUA" ]; then
  mkdir -p "$CACHE/lua50" && (cd "$CACHE/lua50" && curl -sSfLO https://www.lua.org/ftp/lua-5.0.3.tar.gz \
    && echo "1193a61b0e08acaa6eee0eecf29709179ee49c71baebc59b682a25c3b5a45671  lua-5.0.3.tar.gz" | sha256sum -c - >/dev/null \
    && tar xzf lua-5.0.3.tar.gz && make -s -C lua-5.0.3 >/dev/null 2>&1)
fi
(cd "$HERE" && "$LUA" test_gamerules.lua "$ROOT/mod/FarCryCoop/Scripts/Coop/GameRules.lua"); res $?

step "audit_scripts.py"
(cd "$ROOT" && uv run --quiet --with pytest pytest -q tests/test_audit_scripts.py); res $?

step "CryGame.dll load test under wine"
mkdir -p "$TMP/dll"
i686-w64-mingw32-gcc -O2 -shared -o "$TMP/dll/CrySystem.dll" "$HERE/wine/stub_crysystem.c" -Wl,--kill-at \
  && i686-w64-mingw32-gcc -O2 -o "$TMP/dll/load_crygame.exe" "$HERE/wine/load_crygame.c"
for v in stock coop; do
  dll="$ROOT/dist/build/$v/CryGame.dll"
  if [ -f "$dll" ]; then
    cp "$dll" "$TMP/dll/CryGame.dll"; echo "[$v]"; (cd "$TMP/dll" && wine load_crygame.exe CryGame.dll); res $?
  else
    echo "[$v] not built ($dll)"; status=1
  fi
done

step "FarCryCoop.exe under wine"
bash "$HERE/run_launcher_tests.sh" "$ROOT/dist/build"; res $?

rm -rf "$TMP"
echo; [ $status = 0 ] && echo "ALL PASSED" || echo "SOME TESTS FAILED"
exit $status
