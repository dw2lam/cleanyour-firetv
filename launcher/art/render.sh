#!/usr/bin/env bash
# Re-render the launcher's banner and icon from art/*.html with headless Chrome (macOS path; set CHROME elsewhere).
set -euo pipefail
cd "$(dirname "$0")"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
TMP="$(mktemp -d)"
shot() { "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --user-data-dir="$TMP/profile" --window-size="$2" --screenshot="$TMP/$3" "file://$PWD/$1" >/dev/null 2>&1; }
shot banner.html 640,360 banner.png
shot icon.html 512,512 icon.png
RES=../app/src/main/res
sips -s format jpeg -s formatOptions 92 "$TMP/banner.png" --out "$RES/drawable-nodpi/banner.jpg" >/dev/null
sips -s format jpeg -s formatOptions 92 -Z 192 "$TMP/icon.png" --out "$RES/mipmap-xhdpi/ic_launcher.jpg" >/dev/null
rm -rf "$TMP"
echo "rendered banner.jpg (640x360) and ic_launcher.jpg (192x192)"
