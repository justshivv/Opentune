#!/usr/bin/env bash
# Wraps the app image Compose builds (its own Java runtime, FFmpeg and all) in an AppImage:
# one file that runs on any x86-64 Linux after chmod +x, with nothing installed.
#
#   desktop/packaging/appimage.sh <app dir from createDistributable> <output .AppImage>
#
# Downloads appimagetool on first use. Runs it extracted, so no FUSE is needed to build.
set -euo pipefail

app=$(cd "$1" && pwd)
out=$2
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

appdir="$work/OpenTune.AppDir"
mkdir -p "$appdir"
cp -a "$app" "$appdir/opentune"

cat > "$appdir/AppRun" <<'EOF'
#!/bin/sh
here=$(dirname "$(readlink -f "$0")")
exec "$here/opentune/bin/OpenTune" "$@"
EOF
chmod +x "$appdir/AppRun"

cat > "$appdir/opentune.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=OpenTune
Comment=A music player for YouTube Music
Exec=OpenTune
Icon=opentune
Categories=AudioVideo;Audio;Player;
Terminal=false
EOF
cp "$here/icons/AppIcon.png" "$appdir/opentune.png"
ln -s opentune.png "$appdir/.DirIcon"

# A fixed appimagetool release, checked against its SHA-256 before it runs: never the moving
# "continuous" build, and never a download that doesn't match. To move to a newer release,
# change both lines together. APPIMAGETOOL still points at a copy of your own instead.
APPIMAGETOOL_VERSION=1.9.0
APPIMAGETOOL_SHA256=46fdd785094c7f6e545b61afcfb0f3d98d8eab243f644b4b17698c01d06083d1
tool="${APPIMAGETOOL:-$work/appimagetool}"
if [ ! -x "$tool" ]; then
  curl -fsSL -o "$tool.download" "https://github.com/AppImage/appimagetool/releases/download/$APPIMAGETOOL_VERSION/appimagetool-x86_64.AppImage"
  if ! echo "$APPIMAGETOOL_SHA256  $tool.download" | sha256sum -c --status -; then
    rm -f "$tool.download"
    echo "appimagetool $APPIMAGETOOL_VERSION didn't match its pinned SHA-256; not running it" >&2
    exit 1
  fi
  mv "$tool.download" "$tool"
  chmod +x "$tool"
fi
mkdir -p "$(dirname "$out")"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --no-appstream "$appdir" "$out"
echo "Built $out"
