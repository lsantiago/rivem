#!/usr/bin/env bash
# Downloads RIVeR's pisco example frames (commit ea65612) into Examples/pisco/frames, bundled in the app for the
# "Instalar ejemplo" button. Run from ios/.
set -euo pipefail
BASE=https://raw.githubusercontent.com/oruscam/RIVeR/ea65612/examples/data/frames/pisco
DEST=Examples/pisco/frames
mkdir -p "$DEST"
for i in $(seq 0 2 58); do
  name=$(printf "%010d.jpg" "$i")
  [ -s "$DEST/$name" ] || curl -fsSL --retry 3 -o "$DEST/$name" "$BASE/$name"
done
ls "$DEST" | wc -l
