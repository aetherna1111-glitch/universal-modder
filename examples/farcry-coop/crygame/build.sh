#!/usr/bin/env bash
# Builds Mods/FarCryCoop/Bin32/CryGame.dll on Linux with clang-cl + lld-link (MSVC ABI, i686).
#
# Inputs (environment):
#   FC_SDK_SRC   folder that holds "CryGame C++/Solution1" from the CryENGINE MOD SDK 1.4.
#                Default: fetched from github.com/fholger/farcry_vrmod at commit ef7b86c
#                (the stock SDK 1.4 sources plus the VS2022/STLport compile fixes).
#   XWIN         MSVC CRT + Windows SDK splatted by `xwin --arch x86 splat` (default ~/xwin).
#   STOCK=1      build the unmodified SDK DLL (baseline), no co-op patches.
#   JOBS         parallel compile jobs (default nproc).
# Output: $OUT (default ../dist/build/<coop|stock>/CryGame.dll) plus build/<variant>/CryGame.map.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="${FC_CACHE:-$HOME/.cache/farcry-coop}"
XWIN="${XWIN:-$HOME/xwin}"
JOBS="${JOBS:-$(nproc)}"
CLANG_CL="${CLANG_CL:-$(command -v clang-cl || command -v clang-cl-18 || echo /usr/lib/llvm-18/bin/clang-cl)}"
LLD_LINK="${LLD_LINK:-$(command -v lld-link || command -v lld-link-18)}"
SDK_COMMIT=ef7b86c

if [ -z "${FC_SDK_SRC:-}" ]; then
  FC_SDK_SRC="$CACHE/sdk-$SDK_COMMIT/Sources"
  if [ ! -d "$FC_SDK_SRC/CryGame C++/Solution1" ]; then
    mkdir -p "$CACHE"
    [ -d "$CACHE/farcry_vrmod.git" ] || git clone -q --bare https://github.com/fholger/farcry_vrmod.git "$CACHE/farcry_vrmod.git"
    mkdir -p "$CACHE/sdk-$SDK_COMMIT"
    git --git-dir="$CACHE/farcry_vrmod.git" archive --format=tar "$SDK_COMMIT" "Sources/CryGame C++" EULA.txt \
      | tar -x -C "$CACHE/sdk-$SDK_COMMIT"
  fi
fi
for d in "$XWIN/crt/include" "$XWIN/sdk/include/ucrt" "$FC_SDK_SRC/CryGame C++/Solution1/CryGame"; do
  [ -d "$d" ] || { echo "missing: $d" >&2; exit 1; }
done

VARIANT=$([ "${STOCK:-0}" = 1 ] && echo stock || echo coop)
OUT="${OUT:-$HERE/../dist/build/$VARIANT/CryGame.dll}"
WORK="$HERE/build/$VARIANT"
rm -rf "$WORK/src"; mkdir -p "$WORK/src" "$WORK/obj"
cp -r "$FC_SDK_SRC/CryGame C++/Solution1/." "$WORK/src/"
SRC="$WORK/src"
python3 "$HERE/toolchain_fixes.py" "$SRC"

if [ "$VARIANT" = coop ]; then
  for p in "$HERE"/patches/*.patch; do
    [ -e "$p" ] || continue
    patch -s -p1 -d "$SRC/CryGame" < "$p" || { echo "patch failed: $p" >&2; exit 1; }
  done
  cp "$HERE"/coop/*.cpp "$HERE"/coop/*.h "$SRC/CryGame/"
fi

# The sources were written on Windows: include names do not always match the file case.
python3 "$HERE/fix_case.py" "$SRC" "$XWIN"

FILES=$(grep -o 'ClCompile Include="[^"]*"' "$SRC/CryGame/CryGame.vcxproj" | sed 's/ClCompile Include=//;s/"//g')
[ "$VARIANT" = coop ] && FILES="$FILES $(cd "$HERE/coop" && ls *.cpp)"
[ -n "${ONLY:-}" ] && FILES="$ONLY"

CFLAGS=(--target=i686-pc-windows-msvc -fms-compatibility -fms-extensions -fms-compatibility-version=19.30
  /c /O2 /Oy /GS- /EHsc /MT /Zc:wchar_t /W0 -Wno-everything /GF /Gy /arch:IA32
  /D_RELEASE /DNDEBUG /DUSE_GAME_DLL /DWIN32 /D_WINDOWS /D_USRDLL /DCRYGAME_EXPORTS /D_MBCS
  /D_SILENCE_STDEXT_HASH_DEPRECATION_WARNINGS /D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH /D_CRT_SECURE_NO_WARNINGS
  /I"$SRC/STLPORT/stlport" /I"$SRC/CryCommon" /I"$SRC/CryGame"
  /imsvc"$XWIN/crt/include" /imsvc"$XWIN/sdk/include/ucrt" /imsvc"$XWIN/sdk/include/um" /imsvc"$XWIN/sdk/include/shared")

compile() {
  local f="$1" o="$WORK/obj/${1%.cpp}.obj"
  "$CLANG_CL" "${CFLAGS[@]}" "$SRC/CryGame/$f" /Fo"$o" > "$WORK/obj/${1%.cpp}.log" 2>&1 \
    || { echo "FAILED $f"; head -40 "$WORK/obj/${1%.cpp}.log"; return 1; }
}
export -f compile; export WORK SRC CLANG_CL
fail=0
printf '%s\n' $FILES | xargs -P "$JOBS" -I{} bash -c "$(declare -p CFLAGS); compile {}" > "$WORK/compile.log" 2>&1 || fail=1
[ $fail = 0 ] || { grep -c '^FAILED' "$WORK/compile.log" | sed 's/^/failed files: /'; head -80 "$WORK/compile.log"; }
[ $fail = 0 ] || { echo "compile errors" >&2; exit 1; }

[ -n "${ONLY:-}" ] && { echo "compiled $ONLY"; exit 0; }
mkdir -p "$(dirname "$OUT")"
OBJS=$(printf "$WORK/obj/%s.obj " $(printf '%s\n' $FILES | sed 's/\.cpp$//'))
"$LLD_LINK" /nologo /DLL /MACHINE:X86 /OPT:REF /OPT:ICF /SAFESEH:NO /OUT:"$OUT" /MAP:"$WORK/CryGame.map" \
  /LIBPATH:"$XWIN/crt/lib/x86" /LIBPATH:"$XWIN/sdk/lib/um/x86" /LIBPATH:"$XWIN/sdk/lib/ucrt/x86" \
  $OBJS shlwapi.lib wininet.lib ws2_32.lib user32.lib kernel32.lib advapi32.lib gdi32.lib winmm.lib
echo "built $OUT ($VARIANT)"
