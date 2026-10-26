#!/usr/bin/env bash
# Builds opentune-addon-vps.tar.gz: everything the VPS needs, in one folder.
#   bash services/lossless-addon/vps/pack.sh [output directory]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$(dirname "$HERE")"
OUT="${1:-$PWD}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir "$STAGE/opentune-addon"
cp "$SRC"/server.mjs "$SRC"/worker.mjs "$SRC"/cache.mjs "$SRC"/pool.mjs "$SRC"/usage-server.mjs "$SRC"/auth.mjs "$SRC"/security.mjs "$SRC"/attestation.mjs "$SRC"/control.mjs "$SRC"/control.html "$SRC"/control.js "$SRC"/control.css "$STAGE/opentune-addon/"
mkdir -p "$OUT" "$STAGE/opentune-addon/usage-api/dashboard"
USAGE="$SRC/../../usage-api"
cp "$USAGE"/worker.mjs "$USAGE"/auth.mjs "$USAGE"/addons.mjs "$USAGE"/schema.sql "$STAGE/opentune-addon/usage-api/"
cp "$USAGE"/dashboard/index.html "$USAGE"/dashboard/dashboard.js "$USAGE"/dashboard/dashboard.css "$USAGE"/dashboard/model.mjs "$USAGE"/dashboard/session.mjs "$STAGE/opentune-addon/usage-api/dashboard/"
cp "$HERE"/import-usage.mjs "$STAGE/opentune-addon/"
cp "$HERE"/install.sh "$HERE"/opentune-addon.env "$HERE"/README.md "$STAGE/opentune-addon/"
tar -czf "$OUT/opentune-addon-vps.tar.gz" -C "$STAGE" opentune-addon
echo "$OUT/opentune-addon-vps.tar.gz"

python3 - "$STAGE" "$OUT" <<'PYZIP'
import pathlib,sys,zipfile,hashlib
stage,out=map(pathlib.Path,sys.argv[1:])
archive=out/'opentune-hostinger.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for f in sorted((stage/'opentune-addon').rglob('*')):
  if f.is_file(): z.write(f,f.relative_to(stage))
(out/'opentune-hostinger.zip.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
print(archive)
PYZIP
