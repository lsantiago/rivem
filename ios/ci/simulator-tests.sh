#!/usr/bin/env bash
# Usage: simulator-tests.sh <simulator-udid> <path/to/RIVEM.app>
# Installs and launches the app, copies the desktop fixture project (../ci/testdata/desktop-projects/oblique-pisco)
# into the app's workspace, and runs in one launch:
#   -autotest_pipeline 1  synthetic end-to-end measurement (frames → UAV geometry → section + mask → PIV → discharge,
#                         checked against the analytic discharge; project kept as Documents/RIVeR/autotest_sintetico)
#   -autotest_selfcheck 1 core self-check + opening every project of the workspace
# Then it takes screenshots of the list, the fixture project and every workflow step of the synthetic project
# (-autotest_open_project <name> -autotest_step <step>), and checks that opening the desktop fixture did not modify it.
set -u
UDID="$1"
APP="$2"
BUNDLE=com.rivem.app
FIXTURE=../ci/testdata/desktop-projects/oblique-pisco
# RIVeR's example nadir video (downloaded by ios.yml); when present, -autotest_video extracts real frames from it.
VIDEO=${RIVEM_TEST_VIDEO:-build-data/canuelas.mp4}
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

# ---------- synthetic pipeline + real video + self-check + project checks ----------
VIDEO_ARGS=()
if [ -f "$VIDEO" ]; then
  cp "$VIDEO" "$DATA/Documents/$(basename "$VIDEO")"
  VIDEO_ARGS=(-autotest_video "$(basename "$VIDEO")")
else
  echo "No test video at $VIDEO: the real-video extraction check is skipped"
fi
rm -f "$DATA/Documents/autotest_done" "$DATA/Documents/autotest_summary.txt"
xcrun simctl launch "$UDID" "$BUNDLE" -autotest_selfcheck 1 -autotest_pipeline 1 -autotest_example 1 "${VIDEO_ARGS[@]}"
done_flag=0
for _ in $(seq 1 450); do
  sleep 2
  if [ -f "$DATA/Documents/autotest_done" ]; then done_flag=1; break; fi
done
sleep 2
xcrun simctl io "$UDID" screenshot screenshots/01_list.png 2>/dev/null
for f in autotest_summary.txt autotest_done; do
  cp "$DATA/Documents/$f" "screenshots/$f" 2>/dev/null || true
done
xcrun simctl terminate "$UDID" "$BUNDLE" || true

# ---------- screenshots of a project at a given step ----------
shot() { # <file> <project name> <step> [seconds] [extra launch arguments...]
  xcrun simctl launch "$UDID" "$BUNDLE" -autotest_open_project "$2" -autotest_step "$3" "${@:5}"
  sleep "${4:-7}"
  xcrun simctl io "$UDID" screenshot "screenshots/$1.png" 2>/dev/null
  xcrun simctl terminate "$UDID" "$BUNDLE" || true
}

shot 02_detail pisco summary
shot 03_workflow autotest_sintetico list
shot 04_video autotest_sintetico video
shot 05_geometry autotest_sintetico geometry
shot 06_sections autotest_sintetico sections
shot 07_processing autotest_sintetico processing
shot 08_results autotest_sintetico results 10
shot 09_export autotest_sintetico export
if [ -f "$VIDEO" ]; then
  shot 10_video_frames autotest_video video
  shot 11_video_geometry autotest_video geometry
  shot 12_video_sections autotest_video sections
fi
shot 13_example_list Ejemplo_Pisco list
shot 14_example_geometry Ejemplo_Pisco geometry
shot 15_example_sections Ejemplo_Pisco sections
shot 16_example_results Ejemplo_Pisco results 12
shot 17_example_station Ejemplo_Pisco results 12 -autotest_station 6
shot 18_example_vectors Ejemplo_Pisco results 12 -autotest_results_vectors 1
shot 19_dark_results Ejemplo_Pisco results 12 -appearance dark
shot 20_dark_station Ejemplo_Pisco results 12 -appearance dark -autotest_station 6
shot 21_dark_list Ejemplo_Pisco list 8 -appearance dark
shot 22_example_video Ejemplo_Pisco video 8
shot 23_example_processing Ejemplo_Pisco processing 10
shot 24_example_geometry_wizard Ejemplo_Pisco geometry 8
cp "$DATA/Documents/Ejemplo_Pisco_informe.pdf" screenshots/ 2>/dev/null || true

# The app must not have rewritten the desktop files just by opening them.
for f in settings.json xsections.json transformation_matrix.json; do
  if ! cmp -s "$FIXTURE/$f" "$PROJ/$f"; then
    echo "The app modified $f when only opening the project"
    STATUS=1
  fi
done

# Synthetic project as written by the app (small: 12 frames), for inspection or to open in RIVeR desktop.
SYN=$(ls -d "$DATA/Documents/RIVeR/autotest_sintetico"/*/ 2>/dev/null | head -1)
if [ -n "$SYN" ]; then
  mkdir -p screenshots/autotest_sintetico
  cp -R "$SYN" screenshots/autotest_sintetico/ 2>/dev/null || true
fi
cp "$DATA/Documents/logs/rivem.log" screenshots/rivem.log 2>/dev/null || true
xcrun simctl spawn "$UDID" log show --last 15m --predicate 'process == "RIVEM"' --style compact > screenshots/app.log 2>/dev/null || true

echo "===== SELF-CHECK + PIPELINE ====="
cat screenshots/autotest_summary.txt 2>/dev/null || echo "(no summary)"
echo
if [ "$done_flag" != 1 ] || [ "$(cat screenshots/autotest_done 2>/dev/null)" != "ok" ]; then
  echo "Self-check / pipeline did not finish correctly"
  STATUS=1
fi
if ! grep -q "^PASS proyecto pisco/20250310T1542" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The desktop fixture project was not opened"
  STATUS=1
fi
if ! grep -q "^PASS pipeline caudal" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The synthetic discharge check did not pass"
  STATUS=1
fi
if [ -f "$VIDEO" ] && ! grep -q "^PASS video miniatura del lienzo" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The real-video frames/thumbnail check did not pass"
  STATUS=1
fi
if [ -f "$VIDEO" ] && ! grep -q "^PASS video extraccion alternativa" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The AVAssetImageGenerator fallback check did not pass"
  STATUS=1
fi
if ! grep -q "^PASS ejemplo caudal" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The pisco example (install + PIV + discharge) did not pass"
  STATUS=1
fi
if ! grep -q "^PASS ejemplo calidad" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "The pisco example quality / uncertainty check did not pass"
  STATUS=1
fi
if grep -q "^FAIL" screenshots/autotest_summary.txt 2>/dev/null; then
  echo "Some checks failed:"
  grep "^FAIL" screenshots/autotest_summary.txt
  STATUS=1
fi

[ "$STATUS" = 0 ] && echo "All simulator checks passed" || echo "Simulator checks failed"
exit $STATUS
