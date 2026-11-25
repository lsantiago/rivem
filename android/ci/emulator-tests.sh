#!/usr/bin/env bash
# Usage: emulator-tests.sh <path/to/app-debug.apk> [output folder]
# Runs on a booted emulator (android.yml, reactivecircus/android-emulator-runner). Installs the debug APK, launches the
# pisco example autotest (--ez autotest_example true: install the example, PIV, discharge vs RIVeR's notebook, quality,
# PDF report, site checks, desktop compatibility), waits for its summary, then takes a screenshot of every step of the
# example (--es autotest_open_project <name> --es autotest_step <step>), pulls the logs and fails when a check failed,
# the discharge check is missing or the app crashed. Same flow as ios/ci/simulator-tests.sh.
set -u
APK="$1"
OUT="${2:-emulator-out}"
PKG=org.rivem.app
ACTIVITY="$PKG/.MainActivity"
FILES=/sdcard/Android/data/$PKG/files
STATUS=0

mkdir -p "$OUT/screenshots"
adb wait-for-device
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard 2>/dev/null || adb shell input keyevent 82 || true
adb install -r -g "$APK"
adb logcat -c || true

screenshot() { # <file>
  adb exec-out screencap -p > "$OUT/screenshots/$1.png" 2>/dev/null || echo "screencap $1 failed"
}

launch() { # [extras...]
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" "$@" > /dev/null
}

# ---------- plain launch (empty workspace) ----------
launch
sleep 5
screenshot 00_launch

# ---------- pisco example: install + PIV + discharge + quality + report + site ----------
adb shell rm -f "$FILES/autotest_done" "$FILES/autotest_summary.txt"
start=$(date +%s)
launch --ez autotest_example true
done_flag=0
for _ in $(seq 1 300); do
  sleep 3
  if adb shell "test -f $FILES/autotest_done && echo yes" 2>/dev/null | grep -q yes; then done_flag=1; break; fi
  # Stop waiting if the process died.
  if ! adb shell pidof "$PKG" > /dev/null 2>&1; then echo "The app process is gone"; break; fi
done
echo "Example autotest took $(( $(date +%s) - start )) s (done: $done_flag)"
sleep 2
screenshot 01_home_after_example
for f in autotest_summary.txt autotest_done Ejemplo_Pisco_informe.pdf; do
  adb pull "$FILES/$f" "$OUT/$f" > /dev/null 2>&1 || true
done

# ---------- screenshots of the example at each step ----------
shot() { # <file> <project name> <step> [seconds] [extra intent extras...]
  local file="$1" name="$2" step="$3" secs="${4:-6}"
  shift 3; [ $# -gt 0 ] && shift
  launch --es autotest_open_project "$name" --es autotest_step "$step" "$@"
  sleep "$secs"
  screenshot "$file"
}

shot 02_example_list Ejemplo_Pisco list
shot 03_example_summary Ejemplo_Pisco summary
shot 04_example_video Ejemplo_Pisco video 7
shot 05_example_geometry Ejemplo_Pisco geometry 7
shot 06_example_sections Ejemplo_Pisco sections 7
shot 07_example_processing Ejemplo_Pisco processing 7
shot 08_example_results Ejemplo_Pisco results 10
shot 09_example_station Ejemplo_Pisco results 10 --ei autotest_station 6
shot 10_example_vectors Ejemplo_Pisco results 10 --ez autotest_results_vectors true
shot 11_example_quality Ejemplo_Pisco results 10 --ez showQuality true --es autotest_results_scroll quality
shot 12_example_export Ejemplo_Pisco export 6
shot 13_site_list Sitio_Prueba list 6
shot 14_site_curve Sitio_Prueba site 7
shot 15_dark_results Ejemplo_Pisco results 10 --es appearance dark
shot 16_dark_station Ejemplo_Pisco results 10 --es appearance dark --ei autotest_station 6
shot 17_dark_list Ejemplo_Pisco list 6 --es appearance dark
launch
sleep 4
screenshot 18_home
adb shell am force-stop "$PKG"

# ---------- logs ----------
adb exec-out run-as "$PKG" cat files/logs/rivem.log > "$OUT/rivem.log" 2>/dev/null || true
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
adb shell ls -R "$FILES/RIVeR" > "$OUT/workspace-files.txt" 2>/dev/null || true

echo "===== EXAMPLE AUTOTEST ====="
cat "$OUT/autotest_summary.txt" 2>/dev/null || echo "(no summary)"
echo
echo "===== RIVEM log lines ====="
grep -E "RIVEM" "$OUT/logcat.txt" | grep -E "autotest|PIV|Caudal|Calidad|Informe|ERROR| E RIVEM" | tail -60 || true

if [ "$done_flag" != 1 ]; then
  echo "The example autotest did not finish"
  STATUS=1
fi
if ! grep -q "^PASS ejemplo caudal" "$OUT/autotest_summary.txt" 2>/dev/null; then
  echo "The pisco example (install + PIV + discharge) did not pass"
  STATUS=1
fi
if grep -q "^FAIL" "$OUT/autotest_summary.txt" 2>/dev/null; then
  echo "Some checks failed:"
  grep "^FAIL" "$OUT/autotest_summary.txt"
  STATUS=1
fi
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "The app crashed:"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80
  STATUS=1
fi
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then
  echo "The app stopped responding (ANR):"
  grep -A10 "ANR in $PKG" "$OUT/logcat.txt" | head -30
  STATUS=1
fi

[ "$STATUS" = 0 ] && echo "All emulator checks passed" || echo "Emulator checks failed"
exit $STATUS
