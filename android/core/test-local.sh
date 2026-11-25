#!/usr/bin/env bash
# Compiles and runs the core tests with kotlinc + JUnit 4, without Gradle or the Android SDK (CI uses Gradle).
#
#   JUNIT_DIR=<folder with junit-4.13.2.jar and hamcrest-core-1.3.jar> bash core/test-local.sh [TestClass ...]
#
# PKGS="geometry imaging" limits the main sources to RiverCore.kt + those packages (so work in progress in other
# packages does not break the build); default: every package. With no class names, every *Tests.kt that compiles
# with the selected packages must be named explicitly when PKGS is set; without PKGS every *Tests.kt runs.
set -euo pipefail
cd "$(dirname "$0")"
: "${JUNIT_DIR:?set JUNIT_DIR to the folder holding junit-4.13.2.jar and hamcrest-core-1.3.jar}"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
JARS="$JUNIT_DIR/junit-4.13.2.jar$SEP$JUNIT_DIR/hamcrest-core-1.3.jar"
OUT="${TMPDIR:-/tmp}/rivem-core-test-$$"
rm -rf "$OUT"; mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
[ "$SEP" = ";" ] && OUT="$(cygpath -m "$OUT")"
JAVA="java"; [ -n "${JAVA_HOME:-}" ] && JAVA="$JAVA_HOME/bin/java"

MAIN="src/main/kotlin/org/rivem/core"
if [ -n "${PKGS:-}" ]; then
  SOURCES=("$MAIN/RiverCore.kt")
  for p in $PKGS; do SOURCES+=("$MAIN/$p"); done
  TESTS=("src/test/kotlin/org/rivem/core/TestData.kt")
  for c in "$@"; do TESTS+=("src/test/kotlin/org/rivem/core/$c.kt"); done
else
  SOURCES=("src/main/kotlin")
  TESTS=("src/test/kotlin")
fi
# Compile against JUnit only (Windows' kotlinc.bat splits arguments at ';').
kotlinc "${SOURCES[@]}" "${TESTS[@]}" -cp "$JUNIT_DIR/junit-4.13.2.jar" -include-runtime -d "$OUT/core.jar" 2>&1 | grep -v "^warning:" || true
[ -f "$OUT/core.jar" ] || { echo "compilation failed"; exit 1; }
if [ "$#" -gt 0 ]; then
  CLASSES=$(for c in "$@"; do echo "org.rivem.core.$c"; done)
else
  CLASSES=$(cd src/test/kotlin && find . -name "*Tests.kt" | sed 's|^\./||; s|\.kt$||; s|/|.|g')
fi
"$JAVA" -Drivem.testdata="$(cd ../../ci/testdata && pwd -W 2>/dev/null || pwd)" -cp "$OUT/core.jar$SEP$JARS" org.junit.runner.JUnitCore $CLASSES
