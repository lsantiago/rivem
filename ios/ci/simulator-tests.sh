#!/usr/bin/env bash
# Usage: simulator-tests.sh <simulator-udid> <path/to/RIVEM.app>
# Installs and launches the app, takes screenshots and runs the on-device self-check (-autotest_selfcheck),
# then collects autotest_summary.txt / autotest_done from the app's Documents folder.
set -u
UDID="$1"
APP="$2"
BUNDLE=com.rivem.app
STATUS=0

mkdir -p screenshots
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl install "$UDID" "$APP"

# ---------- plain launch ----------
xcrun simctl launch "$UDID" "$BUNDLE"
sleep 6
xcrun simctl io "$UDID" screenshot screenshots/00_launch.png
xcrun simctl terminate "$UDID" "$BUNDLE" || true

# ---------- self-check ----------
DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)
rm -f "$DATA/Documents/autotest_done" "$DATA/Documents/autotest_summary.txt"
xcrun simctl launch "$UDID" "$BUNDLE" -autotest_selfcheck 1
done_flag=0
for _ in $(seq 1 30); do
  sleep 2
  if [ -f "$DATA/Documents/autotest_done" ]; then done_flag=1; break; fi
done
xcrun simctl io "$UDID" screenshot screenshots/01_selfcheck.png 2>/dev/null
for f in autotest_summary.txt autotest_done; do
  cp "$DATA/Documents/$f" "screenshots/$f" 2>/dev/null || true
done
xcrun simctl spawn "$UDID" log show --last 10m --predicate 'process == "RIVEM"' --style compact > screenshots/app.log 2>/dev/null || true

echo "===== SELF-CHECK ====="
cat screenshots/autotest_summary.txt 2>/dev/null || echo "(no summary)"
if [ "$done_flag" != 1 ] || [ "$(cat screenshots/autotest_done 2>/dev/null)" != "ok" ]; then
  echo "Self-check did not finish correctly"
  STATUS=1
fi

[ "$STATUS" = 0 ] && echo "All simulator checks passed" || echo "Simulator checks failed"
exit $STATUS
