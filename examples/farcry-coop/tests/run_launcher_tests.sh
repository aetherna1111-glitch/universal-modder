#!/usr/bin/env bash
# Runs FarCryCoop.exe under wine against a fake Far Cry folder (test fixture, no game files) and checks
# detection, level listing, install + backup, host/join command lines, validation and LAN discovery.
# Needs: wine (32-bit), mingw-w64. Usage: tests/run_launcher_tests.sh [path to dist/build]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="${1:-$HERE/../dist/build}"
WORK="$HERE/../dist/launcher-test"
export WINEPREFIX="${WINEPREFIX:-$HOME/.wine-fc}" WINEDEBUG=-all WINEARCH=win32
CC=i686-w64-mingw32-gcc; WINDRES=i686-w64-mingw32-windres
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "ok   - $1"; }
bad() { fail=$((fail+1)); echo "FAIL - $1"; [ -f "$LOG" ] && tail -5 "$LOG" | sed 's/^/       /'; }

rm -rf "$WORK"; mkdir -p "$WORK/pkg/mod" "$WORK/game/Bin32" "$WORK/game/Mods" "$WORK/game13/Bin32"
# package as shipped: FarCryCoop.exe + mod/FarCryCoop
cp "$BUILD/FarCryCoop.exe" "$WORK/pkg/"
cp -r "$HERE/../mod/FarCryCoop" "$WORK/pkg/mod/"
mkdir -p "$WORK/pkg/mod/FarCryCoop/Bin32"
cp "$BUILD/coop/CryGame.dll" "$WORK/pkg/mod/FarCryCoop/Bin32/" 2>/dev/null || echo "dummy" > "$WORK/pkg/mod/FarCryCoop/Bin32/CryGame.dll"
LOG="$WORK/pkg/FarCryCoop.log"

# fake game folders
for v in "1 4 game" "1 3 game13"; do
  set -- $v
  $WINDRES -DFAKE_MAJOR=$1 -DFAKE_MINOR=$2 "$HERE/wine/fake_farcry.rc" -O coff -o "$WORK/fake$1$2.res"
  $CC -O2 -mwindows -o "$WORK/$3/Bin32/FarCry.exe" "$HERE/wine/fake_farcry.c" "$WORK/fake$1$2.res"
done
mkdir -p "$WORK/game/Levels/pier" "$WORK/game/Levels/training" "$WORK/game/Levels/bad name" "$WORK/game/Levels/notalevel"
touch "$WORK/game/Levels/pier/LevelData.xml" "$WORK/game/Levels/training/level.pak" "$WORK/game/Levels/bad name/LevelData.xml"
G="$(winepath -w "$WORK/game")"; G13="$(winepath -w "$WORK/game13")"
run() { : > "$LOG"; (cd "$WORK/pkg" && timeout 60 wine FarCryCoop.exe "$@" >/dev/null 2>&1); echo $?; }

[ "$(run --game "$G" --check)" = 4 ] && ok "check before install exits 4 (not ready)" || bad "check before install"
grep -q "version 1.4.0.0" "$LOG" && ok "reads FarCry.exe version 1.4" || bad "version read"
grep -q "LEVEL pier" "$LOG" && grep -q "LEVEL training" "$LOG" && ok "lists level folders" || bad "level list"
! grep -q "LEVEL notalevel" "$LOG" && ! grep -q "LEVEL bad name" "$LOG" && grep -q "skipping level folder 'bad name'" "$LOG" \
  && ok "skips non-levels and unsafe names" || bad "level filtering"

[ "$(run --game "$G" --host pier --dry-run)" = 1 ] && grep -q "not installed" "$LOG" && ok "host refuses without the mod installed" || bad "host without mod"

[ "$(run --game "$G" --install)" = 0 ] && ok "install exits 0" || bad "install"
cmp -s "$WORK/pkg/mod/FarCryCoop/Scripts/Coop/GameRules.lua" "$WORK/game/Mods/FarCryCoop/Scripts/Coop/GameRules.lua" \
  && [ -f "$WORK/game/Mods/FarCryCoop/Bin32/CryGame.dll" ] && ok "mod files copied into Mods/FarCryCoop" || bad "copied files"
[ "$(run --game "$G" --check)" = 0 ] && ok "check after install exits 0 (ready)" || bad "check after install"
[ "$(run --game "$G" --install)" = 0 ] && grep -q "already installed and up to date" "$LOG" && ok "reinstall of the same build is a no-op" || bad "idempotent install"

echo "-- local edit" >> "$WORK/game/Mods/FarCryCoop/Scripts/Coop/GameRules.lua"
[ "$(run --game "$G" --install)" = 0 ] && ls -d "$WORK/game/Mods/FarCryCoop.backup-"* >/dev/null 2>&1 \
  && grep -q -- "-- local edit" "$WORK"/game/Mods/FarCryCoop.backup-*/Scripts/Coop/GameRules.lua \
  && ! grep -q -- "-- local edit" "$WORK/game/Mods/FarCryCoop/Scripts/Coop/GameRules.lua" \
  && ok "changed install is backed up before updating" || bad "backup on update"
[ "$(ls "$WORK/game" | sort | tr '\n' ' ')" = "Bin32 Levels Mods " ] && ok "nothing written outside Mods" || bad "wrote outside Mods: $(ls "$WORK/game")"

[ "$(run --game "$G" --host pier --mission Mission2 --max 6 --port 49010 --name "Jack and Val" --password pw1 --dry-run)" = 0 ] \
  && grep -q -- '-MOD:FarCryCoop -coophost:pier:Mission2 -coopmax:6 -coopport:49010 "-coopname:Jack and Val" -cooppass:pw1' "$LOG" \
  && ok "host command line" || bad "host command line"
[ "$(run --game "$G" --join 192.168.1.20 --dry-run)" = 0 ] && grep -q -- '-MOD:FarCryCoop -coopjoin:192.168.1.20' "$LOG" \
  && ok "join command line" || bad "join command line"

[ "$(run --game "$G" --host 'pier";System:Quit()--' --dry-run)" = 2 ] && ok "rejects unsafe map name" || bad "unsafe map"
[ "$(run --game "$G" --join '1.2.3.4;rm' --dry-run)" = 2 ] && ok "rejects unsafe address" || bad "unsafe address"
[ "$(run --game "$G" --host pier --max 40 --dry-run)" = 2 ] && ok "rejects max players 40" || bad "max players"
[ "$(run --game "$G" --host pier --join 1.2.3.4)" = 2 ] && ok "host and join are exclusive" || bad "exclusive"
[ "$(run --game "$(winepath -w "$WORK")" --check)" = 1 ] && grep -q "is not a Far Cry folder" "$LOG" && ok "clear error for a wrong folder" || bad "wrong folder"

rm -f "$WORK/game/Bin32/launched.txt"
[ "$(run --game "$G" --host training --no-lobby)" = 0 ] && grep -q "Far Cry exited (code 7)" "$LOG" \
  && grep -q -- "-MOD:FarCryCoop -coophost:training" "$WORK/game/Bin32/launched.txt" \
  && grep -qi "cwd=.*game\\\\Bin32" "$WORK/game/Bin32/launched.txt" \
  && ok "really starts Bin32\\FarCry.exe in Bin32 and waits for it" || bad "real launch"

mkdir -p "$WORK/game13/Mods"; cp -r "$WORK/game/Mods/FarCryCoop" "$WORK/game13/Mods/"
[ "$(run --game "$G13" --host pier --dry-run)" = 1 ] && grep -q "needs Far Cry patch 1.4" "$LOG" && ok "refuses a 1.3 FarCry.exe" || bad "version gate"
[ "$(run --game "$G13" --host pier --dry-run --ignore-version)" = 0 ] && ok "--ignore-version overrides the gate" || bad "ignore-version"

# LAN discovery: one launcher answers, another discovers (loopback)
: > "$LOG"
(cd "$WORK/pkg" && timeout 30 wine FarCryCoop.exe --lobby-serve 6 --name "LAN Test" --map pier --port 49001 --max 4 --build abc123 >/dev/null 2>&1) &
SERVE=$!
sleep 3
cp -r "$WORK/pkg" "$WORK/pkg2"; LOG2="$WORK/pkg2/FarCryCoop.log"; : > "$LOG2"
(cd "$WORK/pkg2" && timeout 30 wine FarCryCoop.exe --discover --timeout 1500 >/dev/null 2>&1); DISC=$?
wait $SERVE; SRV=$?
grep -q "SESSION .* name=LAN Test map=pier mission= port=49001 max=4 build=abc123 pw=0" "$LOG2" && [ $DISC = 0 ] \
  && ok "discovery finds the advertised session" || { LOG=$LOG2; bad "discovery (exit $DISC)"; }
[ $SRV = 0 ] && grep -q "lobby: answered probe" "$LOG" && ok "host answered the probe" || bad "host answer (exit $SRV)"
: > "$LOG2"; (cd "$WORK/pkg2" && timeout 30 wine FarCryCoop.exe --discover --timeout 800 >/dev/null 2>&1); DISC=$?
[ $DISC = 3 ] && grep -q "discover: 0 session" "$LOG2" && ok "no hosts: exit 3, nothing invented" || { LOG=$LOG2; bad "empty discovery (exit $DISC)"; }

echo "$pass passed, $fail failed"
[ $fail = 0 ]
