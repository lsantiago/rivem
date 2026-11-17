"""Generates the reference results that validate RiverCore against RIVeR desktop.

Runs RIVeR's own Python core (installed from oruscam/RIVeR at the pinned commit, see
.github/workflows/golden.yml) on the public examples of that repository and writes JSON files under --out:

    <out>/<area>/*.json      one folder per area module (geometry, piv, section)

Each area is a module next to this file exposing ``generate(examples: Path, out: Path) -> list[str]`` (returns
the files it wrote). A failing area does not stop the others; the exit code is non-zero if any failed.

Usage: python tools/golden/run_all.py --examples <RIVeR>/examples --out golden-out [--only geometry,piv]
"""

from __future__ import annotations

import argparse
import importlib
import json
import sys
import time
import traceback
from pathlib import Path

AREAS = ["geometry", "piv", "section"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--examples", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parent))

    wanted = [a for a in AREAS if not args.only or a in args.only.split(",")]
    report = {}
    failed = False
    for area in wanted:
        try:
            module = importlib.import_module(area)
        except ModuleNotFoundError as exc:
            if exc.name == area:
                report[area] = {"status": "missing"}
                continue
            raise
        out = args.out / area
        out.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        try:
            files = module.generate(args.examples, out)
            report[area] = {"status": "ok", "files": files, "seconds": round(time.time() - t0, 1)}
        except Exception:  # noqa: BLE001 - report every area
            failed = True
            report[area] = {"status": "error", "traceback": traceback.format_exc()}
            print(f"[{area}] FAILED\n{traceback.format_exc()}", file=sys.stderr)
        print(f"[{area}] {report[area]['status']}")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.json").write_text(json.dumps(report, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
