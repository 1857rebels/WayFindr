#!/usr/bin/env bash
# Capture Wayfindr dash debug logs from the phone into dash.log
set -e
ADB="$HOME/Android/Sdk/platform-tools/adb"
PHONE=00015356O000598
OUT="$(dirname "$0")/dash.log"

"$ADB" -s "$PHONE" logcat -c          # clear old buffer
echo "Capturing → $OUT   (Ctrl-C to stop)"
"$ADB" -s "$PHONE" logcat -v time \
  DashSession:D DashSocket:D DashWifiManager:D TileProvider:I LocationTracker:D \
  AndroidRuntime:E System.err:W "*:S" | tee "$OUT"
