"""Golden references for F4 (cross-section and discharge): RIVeR's own ``update_current_x_section``.

Runs on RIVeR's ``pisco`` example (``examples/results/pisco/{section,transformation,piv_results}.json`` and
``examples/data/bathymetry/pisco_section.csv``) with the parameters of ``examples/05_discharge_calculation.ipynb``
(fps = 25, step = 2, alpha and num_stations from section.json) for several option combinations, and writes the
enriched section (every per-station array and total, plus ``summary``) to ``<out>/pisco_<combo>.json``:

    {"meta": {...inputs and options...}, "xsections": {"section1": {...}, "summary": {...}}}

NaN values are written as bare ``NaN`` tokens (Python's json default), which RiverCore's JSON reader accepts.
Called by run_all.py in CI only (.github/workflows/golden.yml).
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

FPS = 25.0
STEP = 2

# name -> (interpolate, artificial_seeding, unchecked station indices)
COMBOS = {
    "interp": (True, False, []),
    "nointerp": (False, False, []),
    "nointerp_unchecked": (False, False, [5, 9]),
    "interp_unchecked": (True, False, [5, 9]),
    "seeded_interp": (True, True, []),
    "seeded_nointerp": (False, True, []),
}


def _to_jsonable(value):
    """numpy scalars/arrays -> plain Python."""
    import numpy as np

    if isinstance(value, dict):
        return {k: _to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_to_jsonable(v) for v in value]
    if isinstance(value, np.ndarray):
        return _to_jsonable(value.tolist())
    if isinstance(value, np.bool_):
        return bool(value)
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.floating):
        return float(value)
    return value


def generate(examples: Path, out: Path) -> list[str]:
    import numpy as np
    import scipy
    from river.core.compute_section import update_current_x_section

    examples = Path(examples).resolve()
    results = examples / "results" / "pisco"
    base = json.loads((results / "section.json").read_text())
    matrix = np.array(json.loads((results / "transformation.json").read_text()), dtype=np.float64)
    piv = json.loads((results / "piv_results.json").read_text())

    # `bath` is stored relative to examples/ (data/bathymetry/pisco_section.csv): make it absolute.
    for name, section in base.items():
        if isinstance(section, dict) and "bath" in section:
            section["bath"] = str((examples / section["bath"]).resolve())

    written: list[str] = []
    for combo, (interpolate, seeding, unchecked) in COMBOS.items():
        xsections = copy.deepcopy(base)
        section = xsections["section1"]
        num_stations = int(section["num_stations"])
        alpha = float(section["alpha"])
        if unchecked:
            section["check"] = [i not in unchecked for i in range(num_stations)]
        result = update_current_x_section(
            xsections,
            copy.deepcopy(piv),
            matrix,
            STEP,
            FPS,
            0,
            interpolate=interpolate,
            artificial_seeding=seeding,
            alpha=alpha,
            num_stations=num_stations,
            stats_cache={},
        )
        doc = {
            "meta": {
                "example": "pisco",
                "step": STEP,
                "fps": FPS,
                "alpha": alpha,
                "num_stations": num_stations,
                "interpolate": interpolate,
                "artificial_seeding": seeding,
                "unchecked": unchecked,
                "numpy": np.__version__,
                "scipy": scipy.__version__,
            },
            "xsections": _to_jsonable(result),
        }
        path = Path(out) / f"pisco_{combo}.json"
        path.write_text(json.dumps(doc, indent=1))
        written.append(str(path))
    return written
