#!/usr/bin/env bash
# Builds opentune-addon-only.tar.gz and .zip: the add-on without the owner dashboard or the usage dashboard.
#   bash services/lossless-addon/vps/pack-addon-only.sh [output directory]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$(dirname "$HERE")"
OUT="${1:-$PWD}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$OUT" "$STAGE/opentune-addon"
cp "$SRC"/server.mjs "$SRC"/worker.mjs "$SRC"/cache.mjs "$SRC"/pool.mjs "$SRC"/auth.mjs "$SRC"/security.mjs "$SRC"/attestation.mjs "$STAGE/opentune-addon/"
cp "$HERE"/install.sh "$STAGE/opentune-addon/"
cp "$HERE"/opentune-addon-only.env "$STAGE/opentune-addon/opentune-addon.env"
cp "$HERE"/README-addon-only.md "$STAGE/opentune-addon/README.md"
# Tells the installer this is the add-on alone.
printf 'add-on only: no owner dashboard, no usage dashboard\n' > "$STAGE/opentune-addon/ADDON_ONLY"
tar -czf "$OUT/opentune-addon-only.tar.gz" -C "$STAGE" opentune-addon
echo "$OUT/opentune-addon-only.tar.gz"
python3 - "$STAGE" "$OUT" <<'PYZIP'
import pathlib,sys,zipfile,hashlib
stage,out=map(pathlib.Path,sys.argv[1:])
archive=out/'opentune-addon-only.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for f in sorted((stage/'opentune-addon').rglob('*')):
  if f.is_file(): z.write(f,f.relative_to(stage))
(out/'opentune-addon-only.zip.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
print(archive)
PYZIP
