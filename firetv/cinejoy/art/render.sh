#!/bin/sh
# Re-renders the TV banner and launcher icons from banner.html / icon.html with headless Chrome,
# each at its native pixel size, straight into the app's res/ folders.
set -e
cd "$(dirname "$0")"
ART="$PWD"
RES="$ART/../app/src/main/res"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

shot() { # <out.png> <width> <height> <url>
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --default-background-color=00000000 --virtual-time-budget=3000 \
    --window-size="$2,$3" --screenshot="$1" "$4" 2>/dev/null
  echo "$1 ($2x$3)"
}

# Banner: 320x180 dp.
shot "$RES/drawable-xhdpi/banner.png"  640 360 "file://$ART/banner.html"
shot "$RES/drawable-xxhdpi/banner.png" 960 540 "file://$ART/banner.html"

# Launcher icon: 48 dp.
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  dens=${d%%:*}; px=${d##*:}
  shot "$RES/mipmap-$dens/ic_launcher.png" "$px" "$px" "file://$ART/icon.html?s=$px"
done
