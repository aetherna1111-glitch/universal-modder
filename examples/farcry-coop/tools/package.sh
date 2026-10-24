#!/usr/bin/env bash
# Builds everything and assembles dist/FarCryCoop-<version>/ and a .zip of it:
#   FarCryCoop.exe, README.md, mod/FarCryCoop/{Bin32/CryGame.dll, Scripts, ModDesc.txt, README.txt, EULA.txt},
#   tools/audit_scripts.py
# The CryENGINE MOD SDK EULA is copied from the SDK checkout the DLL was built from (it must accompany the DLL).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=0.1.0
NAME="FarCryCoop-$VERSION"
DIST="$HERE/dist"
OUTDIR="$DIST/$NAME"
CACHE="${FC_CACHE:-$HOME/.cache/farcry-coop}"

bash "$HERE/crygame/build.sh"
bash "$HERE/launcher/build.sh"

EULA="${FC_SDK_EULA:-$CACHE/sdk-ef7b86c/EULA.txt}"
[ -f "$EULA" ] || { echo "SDK EULA not found at $EULA (set FC_SDK_EULA)" >&2; exit 1; }

rm -rf "$OUTDIR" "$DIST/$NAME.zip"
mkdir -p "$OUTDIR/mod" "$OUTDIR/tools"
cp "$DIST/build/FarCryCoop.exe" "$OUTDIR/"
cp -r "$HERE/mod/FarCryCoop" "$OUTDIR/mod/"
mkdir -p "$OUTDIR/mod/FarCryCoop/Bin32"
cp "$DIST/build/coop/CryGame.dll" "$OUTDIR/mod/FarCryCoop/Bin32/"
cp "$EULA" "$OUTDIR/mod/FarCryCoop/EULA.txt"
cp "$HERE/README.md" "$OUTDIR/README.md"
cp "$HERE/tools/audit_scripts.py" "$OUTDIR/tools/"
(cd "$DIST" && rm -f "$NAME.zip" && python3 -c "import shutil; shutil.make_archive('$NAME', 'zip', '.', '$NAME')")
(cd "$OUTDIR" && find . -type f | sort | xargs sha256sum) > "$DIST/$NAME.sha256"
echo "packaged $DIST/$NAME.zip"
cat "$DIST/$NAME.sha256"
