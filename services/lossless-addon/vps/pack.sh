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
cp "$SRC"/server.mjs "$SRC"/worker.mjs "$SRC"/auth.mjs "$SRC"/security.mjs "$SRC"/attestation.mjs "$SRC"/control.mjs "$SRC"/control.html "$SRC"/control.js "$SRC"/control.css "$STAGE/opentune-addon/"
cp "$HERE"/install.sh "$HERE"/opentune-addon.env "$HERE"/README.md "$STAGE/opentune-addon/"
tar -czf "$OUT/opentune-addon-vps.tar.gz" -C "$STAGE" opentune-addon
echo "$OUT/opentune-addon-vps.tar.gz"
