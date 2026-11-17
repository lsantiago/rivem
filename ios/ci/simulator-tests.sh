#!/usr/bin/env bash
# Usage: simulator-tests.sh <simulator-udid> <path/to/RIVEM.app>
# Installs and launches the app, copies the desktop fixture project (../ci/testdata/desktop-projects/oblique-pisco)
# into the app's workspace, runs the on-device self-check (-autotest_selfcheck), which also opens every project,
# and takes screenshots of the list and of the project detail.
set -u
UDID="$1"
APP="$2"
BUNDLE=com.rivem.app
FIXTURE=../ci/testdata/desktop-projects/oblique-pisco
STATUS=0

mkdir -p screenshots
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl install "$UDID" "$APP"

# ---------- plain launch (empty workspace) ----------
xcrun simctl launch "$UDID" "$BUNDLE"
sleep 6
xcrun simctl io "$UDID" screenshot screenshots/00_launch.png
xcrun simctl terminate "$UDID" "$BUNDLE" || true

# ---------- desktop project copied into Documents/RIVeR/<name>/<date> ----------
DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)
PROJ="$DATA/Documents/RIVeR/pisco/20250310T1542"
mkdir -p "$PROJ/frames"
cp "$FIXTURE"/*.json "$FIXTURE"/*.csv "$PROJ/"

# ---------- self-check + project checks ----------
rm -f "$DATA/Documents/autotest_done" "$DATA/Documents/autotest_summary.txt"
xcrun simctl launch "$UDID" "$BUNDLE" -autotest_selfcheck 1
done_flag=0
for _ in $(seq 1 30); do
  sleep 2
  if [ -f "$DATA/Documents/autotest_done" ]; then done_flag=1; break; fi
done
sleep 2
xcrun simctl io "$UDID" screenshot screenshots/01_list.png 2>/dev/null
for f in autotest_summary.txt autotest_done; do
  cp "$DATA/Documents/$f" "screenshots/$f" 2>/dev/null || true
done
xcrun simctl terminate "$UDID" "$BUNDLE" || true

# ---------- project detail screenshot ----------
xcrun simctl launch "$UDID" "$BUNDLE" -autotest_open_first 1
sleep 6
xcrun simctl io "$UDID" screenshot screenshots/02_detail.png 2>/dev/null
xcrun simctl terminate "$UDID" "$BUNDLE" || true

# The app must not have rewritten the desktop files just by opening them.
for f in settings.json xsections.json transformation_matrix.json; do
  if ! cmp -s "$FIXTURE/$f" "$PROJ/$f"; then
    echo "The app modified $f when only opening the project"
    STATUS=1
  fi
done

xcrun simctl spawn "$UDID" log show --last 10m --predicate 'process == "RIVEM"' --style compact > screenshots/app.log 2>/dev/null || true

echo "===== SELF-CHECK ====="
cat screenshots/autotest_summary.txt 2>/dev/null || echo "(no summary)"
if [ "$done_flag" != 1 ] || [ "$(cat screenshots/autotest_done 2>/dev/null)" != "ok" ]; then
  echo "Self-check did not finish correctly"
  STATUS=1
fi
if ! grep -q "^PASS proyecto pisco/20250310T1542" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The desktop fixture project was not opened"
  STATUS=1
fi

[ "$STATUS" = 0 ] && echo "All simulator checks passed" || echo "Simulator checks failed"
exit $STATUS
