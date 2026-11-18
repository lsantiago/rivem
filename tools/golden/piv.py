"""PIV references (F3): RIVeR's own preprocessing and ``piv_fftmulti`` on a crop of the canuelas frames.

Writes into ``out`` (copied to ci/testdata/golden/piv/ and read by ios/Tests/RiverCoreTests/PIVTests.swift):

- ``canuelas_frames.json``: the exact 8-bit grayscale inputs (crop of 3 consecutive frames, base64 row-major
  bytes), so the Swift tests do not need a JPEG decoder.
- ``canuelas_clahe.json``: ``cv2.createCLAHE(clip).apply`` of some of those frames (bit-exact reference).
- ``canuelas_piv_<case>.json``: one PIV case each (parameters, grid, u/v/gradient per pair, medians), computed as
  ``piv_pipeline.run_analyze_all`` + ``piv_loop`` do, but on the in-memory crops: preprocessing with
  ``image_preprocessing`` (background = ``np.uint8(mean)`` of the frames, as ``calculate_average``), then
  ``piv_fftmulti`` with GUI defaults (mask_auto, multipass, std 4, median 0.02/2, ia2 = ia1/2) and the mask applied
  at ``(int(x), int(y))``.

Runs in CI only (.github/workflows/golden.yml) and takes a few seconds.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

import cv2
import numpy as np

FRAMES = ["0000000000.jpg", "0000000002.jpg", "0000000004.jpg"]
# Crop on the water surface of canuelas (1920x1080): x, y, width, height.
CROP = (800, 280, 384, 320)


def _b64(a: np.ndarray) -> str:
    return base64.b64encode(np.ascontiguousarray(a, dtype=np.uint8).tobytes()).decode("ascii")


def _floats(a) -> list:
    return [float(v) for v in np.asarray(a, dtype=float).ravel()]


def _run_pairs(frames, background, case):
    from river.core import image_preprocessing as impp
    from river.core.piv_fftmulti import piv_fftmulti

    h, w = frames[0].shape

    def prep(img):
        img = img.copy()
        if case["remove_background"]:
            img = impp.subtract_background(img, background)
        if case["clahe"]:
            img = impp.create_clahe(case["clip_limit"]).apply(img)
        return img

    pre = [prep(f) for f in frames]
    bbox = case.get("bbox") or [0, 0, w, h]
    mask = np.ones((h, w), dtype=np.uint8)
    if case.get("mask_polygon"):
        mask = np.zeros((h, w), dtype=np.uint8)
        cv2.fillPoly(mask, [np.array(case["mask_polygon"], dtype=np.int32)], 1)
    mask_piv = np.ones((h, w), dtype=np.uint8)
    ia1 = case["interrogation_area_1"]
    pairs = []
    shape = None
    xs = ys = None
    for i in range(len(pre) - 1):
        xt, yt, ut, vt, _, grad = piv_fftmulti(
            pre[i], pre[i + 1], mask=mask_piv, bbox=bbox,
            interrogation_area_1=ia1, interrogation_area_2=ia1 / 2,
            mask_auto=True, multipass=True,
            standard_filter=case["std_filtering"], standard_threshold=case["std_threshold"],
            median_test_filter=case["median_test_filtering"], epsilon=case["median_test_epsilon"],
            threshold=case["median_test_threshold"], step=None,
        )
        xi = np.clip(xt.astype(int), 0, w - 1)
        yi = np.clip(yt.astype(int), 0, h - 1)
        inside = mask[yi, xi] > 0
        ut = ut.astype(float)
        vt = vt.astype(float)
        ut[~inside] = np.nan
        vt[~inside] = np.nan
        shape = list(xt.shape)
        xs, ys = _floats(xt), _floats(yt)
        pairs.append({"u": _floats(ut), "v": _floats(vt), "gradient": _floats(grad)})
    u = np.array([p["u"] for p in pairs])
    v = np.array([p["v"] for p in pairs])
    with np.errstate(all="ignore"):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            um = np.nanmedian(u, axis=0)
            vm = np.nanmedian(v, axis=0)
    out = dict(case)
    out.update({"shape": shape, "x": xs, "y": ys, "pairs": pairs, "u_median": _floats(um), "v_median": _floats(vm)})
    if case.get("mask_polygon"):
        out["mask"] = _b64(mask)
    return out


def generate(examples: Path, out: Path) -> list[str]:
    from river.core import image_preprocessing as impp

    folder = Path(examples) / "data" / "frames" / "canuelas"
    x, y, w, h = CROP
    frames = []
    for name in FRAMES:
        img = cv2.imread(str(folder / name), cv2.IMREAD_GRAYSCALE)
        if img is None:
            raise FileNotFoundError(folder / name)
        frames.append(np.ascontiguousarray(img[y:y + h, x:x + w]))
    written = []

    def dump(name, obj):
        path = out / name
        path.write_text(json.dumps(obj))
        written.append(str(path))

    dump("canuelas_frames.json", {
        "source": "RIVeR examples/data/frames/canuelas", "names": FRAMES, "crop": list(CROP),
        "width": w, "height": h, "frames": [_b64(f) for f in frames],
    })

    clahe_cases = []
    for frame_index, clip in [(0, 5), (1, 2), (2, 40)]:
        res = impp.create_clahe(clip).apply(frames[frame_index])
        clahe_cases.append({"frame": frame_index, "clip_limit": clip, "output": _b64(res)})
    # Non-multiple-of-8 size (BORDER_REFLECT_101 extension of the tile grid).
    odd = np.ascontiguousarray(frames[0][:203, :301])
    clahe_cases.append({"frame": 0, "clip_limit": 5, "crop": [0, 0, 301, 203],
                        "output": _b64(impp.create_clahe(5).apply(odd))})
    dump("canuelas_clahe.json", {"cases": clahe_cases})

    background = np.uint8(np.mean(np.stack([f.astype(np.float64) for f in frames]), axis=0))
    defaults = {
        "interrogation_area_1": 128, "remove_background": False, "clahe": True, "clip_limit": 5,
        "std_filtering": True, "std_threshold": 4, "median_test_filtering": True,
        "median_test_epsilon": 0.02, "median_test_threshold": 2,
    }
    cases = {
        "default": dict(defaults),
        "ia64": dict(defaults, interrogation_area_1=64),
        "background": dict(defaults, interrogation_area_1=64, remove_background=True),
        "bbox_mask": dict(defaults, interrogation_area_1=64, bbox=[10.4, 20.6, 350.0, 280.0],
                          mask_polygon=[[30, 40], [370, 60], [360, 300], [20, 280]]),
    }
    for name, case in cases.items():
        dump(f"canuelas_piv_{name}.json", _run_pairs(frames, background, dict(case, name=name)))
    return written
