#!/usr/bin/env bash
# Build and install the Fire TV setup: the My Home launcher with its Home redirect and Clock screensaver, the
# Cinejoy kiosk app, a debloat list and AdGuard Private DNS. Safe to re-run, e.g. after a Fire OS update or a
# factory reset. Needs ADB debugging on, the "Allow" prompt accepted, JDK 17 and the Android SDK (README.md).
#
#   TV_HOST=192.168.1.50 firetv/install.sh
#   TV_HOST=... LIST=examples/<your-model>/debloat.txt firetv/install.sh   # another model's list
#   TV_HOST=... SKIP_DEBLOAT=1 firetv/install.sh                           # apps only
#   TV_HOST=... SKIP_CINEJOY=1 SKIP_DNS=1 firetv/install.sh
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV's IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
REPO="$(cd "$(dirname "$0")/.." && pwd)"
LIST="${LIST:-examples/amazon-fire-tv-aftalmo/debloat.txt}"
cd "$REPO"
PY=tools/venv/bin/python
[ -x "$PY" ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export JAVA_HOME

case "$("$PY" -I tools/tv.py 'pm list features' 2>/dev/null)" in
  *amazon.hardware.fire_tv*) ;;
  *) echo "ERROR: $TV_HOST is not a Fire TV (no amazon.hardware.fire_tv). Use launcher/build-and-install.sh" >&2
     echo "and the README's Android TV steps instead." >&2; exit 1 ;;
esac

echo "== My Home launcher (Fire OS path: beside Amazon's home, with the Home redirect and Clock screensaver)"
REDIRECT_HOME=1 launcher/build-and-install.sh

if [ "${SKIP_CINEJOY:-0}" != 1 ]; then
  echo "== Cinejoy kiosk"
  (cd firetv/cinejoy
   [ -f local.properties ] || echo "sdk.dir=${ANDROID_HOME:-$HOME/Library/Android/sdk}" > local.properties
   ./gradlew assembleDebug -q --console=plain)
  "$PY" -I tools/b64push.py firetv/cinejoy/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/cinejoy.apk
  "$PY" -I tools/tv.py 'pm install -r /data/local/tmp/cinejoy.apk; rm -f /data/local/tmp/cinejoy.apk'
fi

# After the launcher, so Amazon's screensaver is only disabled once the Clock has taken its place.
if [ "${SKIP_DEBLOAT:-0}" != 1 ]; then
  echo "== debloat list $LIST (one package per call; protected ones are refused and left alone)"
  tools/disable.sh "$LIST" | grep -E 'new state|xception|Skipping' || true
fi

if [ "${SKIP_DNS:-0}" != 1 ]; then
  echo "== AdGuard Private DNS"
  printf '%s\n' "settings put global private_dns_specifier dns.adguard-dns.com" \
    "settings put global private_dns_mode hostname" | "$PY" -I tools/tv.py --batch
fi

echo "Done. If My Home is not first in Amazon's apps row (new install or factory reset): Amazon home > Apps grid >"
echo "highlight My Home > Menu > Move to front. Then switch ADB debugging off again (firetv/README.md, Closing ADB)."
