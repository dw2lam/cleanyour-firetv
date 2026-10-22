#!/usr/bin/env bash
# Undo firetv/install.sh: re-enable every package in the debloat list, turn off the Home redirect, the Clock
# screensaver and AdGuard DNS, and uninstall My Home and Cinejoy (KEEP_APPS=1 keeps those two).
# Apps installed from the Appstore (YouTube, Spotify, Disney+, ...) are left alone.
#
#   TV_HOST=192.168.1.50 firetv/restore.sh
#   TV_HOST=... LIST=examples/<your-model>/debloat.txt firetv/restore.sh
#   TV_HOST=... KEEP_APPS=1 firetv/restore.sh
#
# A backup taken before install (see README.md) has the exact before-state; a device's own restore-all.sh in
# backup/<date>-<tv>/ re-enables whatever was disabled since, list or not.
set -uo pipefail
: "${TV_HOST:?set TV_HOST to the TV's IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
REPO="$(cd "$(dirname "$0")/.." && pwd)"
LIST="${LIST:-examples/amazon-fire-tv-aftalmo/debloat.txt}"
cd "$REPO"
PY=tools/venv/bin/python

echo "== re-enabling the list"
tools/restore.sh "$LIST" com.amazon.tv.launcher

echo "== Home redirect, screensaver, DNS"
printf '%s\n' \
  "settings delete secure enabled_accessibility_services" \
  "settings put secure accessibility_enabled 0" \
  "settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService" \
  "settings put secure screensaver_default_component com.amazon.ftv.screensaver/.app.services.ScreensaverService" \
  "settings put global private_dns_mode opportunistic" \
  "settings delete global private_dns_specifier" | "$PY" -I tools/tv.py --batch >/dev/null

if [ "${KEEP_APPS:-0}" != 1 ]; then
  for p in com.gathin.tvhome online.davidlam.cinejoy; do
    echo "== uninstall $p: $("$PY" -I tools/tv.py "pm uninstall $p" 2>&1 | tr -d '\r' | tail -n 1)"
  done
fi
echo "Done. Press Home on the remote: Amazon's home screen should come up."
