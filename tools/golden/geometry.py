"""Geometry references (F2) computed with RIVeR's own Python core.

Writes, under <out> (= golden-out/geometry, copied to ci/testdata/golden/geometry):

    oblique_pisco.json      4 points + 6 distances (from the pisco matrix), with and without anchors, plus the
                            transformed image of a synthetic gradient frame (grid, sampled pixels)
    camera_full_grp_3d.json direct DLT solve on examples/data/grps/full_grp_3d.json, level matrix, ortho grid
    uav.json                2-point UAV matrices: an East-West case (desktop correct) and a North-South case that
                            documents desktop's mirroring defect
    roi_pisco.json          ROI mask / bbox / recommended height for the pisco section, a user polygon mask and the
                            compiled final mask (masks as count + bounds + per-row counts + md5, not full arrays)
    npy_small.json          exact bytes of np.save for a small uint8 mask

Only needs the installed ``river`` package, numpy and cv2. Read by ios/Tests/RiverCoreTests/GeometryF2Tests.swift.
"""

from __future__ import annotations

import hashlib
import io
import json
import tempfile
from pathlib import Path

import cv2
import numpy as np

from river.core.coordinate_transform import (
    get_camera_solution,
    get_homography_from_camera_matrix,
    get_uav_transformation_matrix,
    oblique_view_transformation_matrix,
    optimize_coordinates,
    orthorectify_image,
    transform_pixel_to_real_world,
)
from river.core.define_roi_masks import (
    compile_masks,
    create_mask_and_bbox,
    create_user_polygon_mask,
    recommend_height_roi,
)

FRAME_W, FRAME_H = 1920, 1080


def synthetic_frame(width: int = FRAME_W, height: int = FRAME_H) -> np.ndarray:
    """RGB gradient the Swift test rebuilds exactly: R = 255*x//(W-1), G = 255*y//(H-1), B = 128."""
    xs = (np.arange(width) * 255) // (width - 1)
    ys = (np.arange(height) * 255) // (height - 1)
    img = np.zeros((height, width, 3), dtype=np.uint8)
    img[:, :, 0] = xs[None, :]
    img[:, :, 1] = ys[:, None]
    img[:, :, 2] = 128
    return img


def write_frame(folder: Path) -> str:
    path = folder / "frame.png"
    cv2.imwrite(str(path), cv2.cvtColor(synthetic_frame(), cv2.COLOR_RGB2BGR))
    return str(path)


def image_summary(img: np.ndarray, n: int = 15) -> dict:
    h, w = img.shape[:2]
    rows = sorted({int(round(v)) for v in np.linspace(0, h - 1, n)})
    cols = sorted({int(round(v)) for v in np.linspace(0, w - 1, n)})
    samples = [[r, c] + [int(v) for v in img[r, c]] for r in rows for c in cols]
    return {
        "width": int(w),
        "height": int(h),
        "opaque_count": int((img[:, :, 3] == 255).sum()),
        "samples": samples,  # [row, col, R, G, B, A]
    }


def mask_summary(mask: np.ndarray) -> dict:
    mask = np.ascontiguousarray(mask, dtype=np.uint8)
    nz = np.argwhere(mask != 0)
    bounds = None
    if len(nz):
        bounds = [int(nz[:, 1].min()), int(nz[:, 0].min()), int(nz[:, 1].max()), int(nz[:, 0].max())]
    rows = mask.astype(bool).sum(axis=1)
    return {
        "width": int(mask.shape[1]),
        "height": int(mask.shape[0]),
        "count": int((mask != 0).sum()),
        "bounds": bounds,  # [xMin, yMin, xMax, yMax] inclusive
        "row_counts": [[int(r), int(c)] for r, c in enumerate(rows) if c],
        "md5": hashlib.md5(mask.tobytes()).hexdigest(),
    }


def tolist(m) -> list:
    return np.asarray(m, dtype=float).tolist()


# --------------------------------------------------------------------------------------------------- oblique


def oblique(examples: Path, out: Path, tmp: Path) -> str:
    matrix = np.array(json.loads((examples / "results/pisco/transformation.json").read_text()), dtype=float)
    # Pixel quadrilateral 1-2-3-4 (counter-clockwise on the water) on the pisco frame; distances taken from the
    # published pisco matrix and rounded to the millimetre, like tape measurements.
    pixels = [[49.94, 935.04], [1800.19, 687.6], [1400.0, 420.0], [450.0, 480.0]]
    world = [transform_pixel_to_real_world(x, y, matrix) for x, y in pixels]

    def dist(i, j):
        return round(float(np.hypot(*(world[i] - world[j]))), 3)

    distances = [dist(0, 1), dist(1, 2), dist(2, 3), dist(3, 0), dist(0, 2), dist(1, 3)]
    frame = write_frame(tmp)
    flat = [v for p in pixels for v in p]

    plain = oblique_view_transformation_matrix(*flat, *distances, image_path=frame, roi_padding=0.0)
    e3, n3, e4, n4 = optimize_coordinates(*distances)

    anchors = [100.0, 200.0, 100.0 + distances[0] * np.cos(0.5), 200.0 + distances[0] * np.sin(0.5)]
    anchored = oblique_view_transformation_matrix(
        *flat, *distances, roi_padding=0.0,
        east1=anchors[0], north1=anchors[1], east2=anchors[2], north2=anchors[3],
        enforce_d12=True, distance_tolerance=1.0,
    )
    data = {
        "source": "oblique_view_transformation_matrix / optimize_coordinates (river.core.coordinate_transform)",
        "frame": {"width": FRAME_W, "height": FRAME_H, "pattern": "R=255*x//(W-1), G=255*y//(H-1), B=128"},
        "pixels": pixels,
        "distances": distances,
        "optimized_p3_p4": [float(e3), float(n3), float(e4), float(n4)],
        "matrix": tolist(plain["transformation_matrix"]),
        "roi": [float(v) for v in plain["roi"]],
        "extent": [float(v) for v in plain["extent"]],
        "output_resolution": float(plain["output_resolution"]),
        "transformed_image": image_summary(plain["transformed_img"]),
        "anchored": {
            "anchors": anchors,
            "enforce_d12": True,
            "distance_tolerance": 1.0,
            "matrix": tolist(anchored["transformation_matrix"]),
            "roi": [float(v) for v in anchored["roi"]],
        },
    }
    path = out / "oblique_pisco.json"
    path.write_text(json.dumps(data, indent=1))
    return path.name


# --------------------------------------------------------------------------------------------------- camera


def camera(examples: Path, out: Path, tmp: Path) -> str:
    grp = json.loads((examples / "data/grps/full_grp_3d.json").read_text())
    grp = {k: grp[k] for k in ("X", "Y", "Z", "x", "y")}
    sol = get_camera_solution(grp, optimize_solution=False)
    P = np.array(sol["camera_matrix"], dtype=float)
    z_level = float(np.mean(grp["Z"]))
    level_matrix = get_homography_from_camera_matrix(P, z_level)  # inv(H), as the GUI computes it
    probe = [[700.0, 600.0], [200.0, 900.0], [1400.0, 300.0], [960.0, 540.0]]
    probe_world = [transform_pixel_to_real_world(x, y, level_matrix).tolist() for x, y in probe]

    frame = write_frame(tmp)
    ortho, extent = orthorectify_image(frame, {"camera_matrix": P}, {k: np.array(v) for k, v in grp.items()},
                                       output_resolution=0.1, flip_x=False, flip_y=True)
    data = {
        "source": "get_camera_solution(direct-solve) / get_homography_from_camera_matrix / orthorectify_image",
        "points": grp,
        "camera_matrix": tolist(P),
        "camera_position": [float(v) for v in sol["camera_position"]],
        "error": float(sol["error"]),
        "reprojection_errors": [float(v) for v in sol["reprojection_errors"]],
        "projected_points": tolist(sol["projected_points"]),
        "uncertainty_ellipses": [
            {"center": [float(c) for c in e["center"]], "width": float(e["width"]), "height": float(e["height"]),
             "angle": float(e["angle"])}
            for e in sol["uncertainty_ellipses"]
        ],
        "z_level": z_level,
        "level_matrix": tolist(level_matrix),
        "probe_pixels": probe,
        "probe_world": probe_world,
        "ortho": {"extent": [float(v) for v in extent], "width": int(ortho.shape[1]), "height": int(ortho.shape[0]),
                  "resolution": 0.1},
    }
    path = out / "camera_full_grp_3d.json"
    path.write_text(json.dumps(data, indent=1))
    return path.name


# --------------------------------------------------------------------------------------------------- uav


def uav(out: Path, tmp: Path) -> str:
    frame = write_frame(tmp)
    ew = dict(pix=[640.0, 540.0, 1670.0, 540.0], rw=[0.0, 0.0, 20.0, 0.0])
    ns = dict(pix=[960.0, 200.0, 960.0, 900.0], rw=[0.0, 0.0, 0.0, -14.0])
    res_ew = get_uav_transformation_matrix(*ew["pix"], *ew["rw"], image_path=frame, roi_padding=0.0)
    res_ns = get_uav_transformation_matrix(*ns["pix"], *ns["rw"])
    m_ns = np.array(res_ns["transformation_matrix"])
    data = {
        "source": "get_uav_transformation_matrix (river.core.coordinate_transform)",
        "east_west": {
            **ew,
            "matrix": tolist(res_ew["transformation_matrix"]),
            "extent": [float(v) for v in res_ew["extent"]],
            "output_resolution": float(res_ew["output_resolution"]),
            "transformed_image": image_summary(res_ew["transformed_img"]),
        },
        "north_south": {
            **ns,
            "matrix": tolist(m_ns),
            # Desktop defect: point 2 does not land on its real-world coordinates (mirrored).
            "desktop_point2_world": transform_pixel_to_real_world(ns["pix"][2], ns["pix"][3], m_ns).tolist(),
        },
    }
    path = out / "uav.json"
    path.write_text(json.dumps(data, indent=1))
    return path.name


# --------------------------------------------------------------------------------------------------- roi


def roi(examples: Path, out: Path) -> str:
    matrix = np.array(json.loads((examples / "results/pisco/transformation.json").read_text()), dtype=float)
    sections = json.loads((examples / "results/pisco/section.json").read_text())
    # Without "bath"/"level" no island exclusion is attempted (the bathymetry path is relative to the notebook).
    plain = {name: {k: v for k, v in s.items() if k not in ("bath", "level")} for name, s in sections.items()}
    height_roi = 4
    image = np.zeros((FRAME_H, FRAME_W, 3), dtype=np.uint8)
    mask, bbox = create_mask_and_bbox(image, plain, matrix, height_roi)
    recommended = recommend_height_roi(plain, 128, matrix)
    polygon = [{"x": 300.4, "y": 500.5}, {"x": 700.6, "y": 450.2}, {"x": 650.5, "y": 801.7}, {"x": 250.0, "y": 760.9}]
    user = create_user_polygon_mask((FRAME_H, FRAME_W), polygon)
    final = compile_masks(roi_mask=mask, user_masks=[user])
    first = next(iter(plain.values()))
    data = {
        "source": "create_mask_and_bbox / recommend_height_roi / create_user_polygon_mask / compile_masks",
        "matrix": tolist(matrix),
        "section": {k: first[k] for k in ("east_l", "north_l", "east_r", "north_r", "rw_length")},
        "height_roi": height_roi,
        "bbox": [float(v) for v in bbox],
        "mask": mask_summary(mask),
        "recommended_height_roi": float(recommended),
        "window_size": 128,
        "user_polygon": [[p["x"], p["y"]] for p in polygon],
        "user_mask": mask_summary(user),
        "final_mask": mask_summary(final),
    }
    path = out / "roi_pisco.json"
    path.write_text(json.dumps(data, indent=1))
    return path.name


def npy_small(out: Path) -> str:
    mask = np.array([[0, 1, 1, 0, 1], [1, 1, 0, 0, 0], [0, 0, 0, 1, 1]], dtype=np.uint8)
    buf = io.BytesIO()
    np.save(buf, mask)
    data = {"source": "numpy.save", "numpy": np.__version__, "width": 5, "height": 3,
            "values": mask.flatten().tolist(), "hex": buf.getvalue().hex()}
    path = out / "npy_small.json"
    path.write_text(json.dumps(data, indent=1))
    return path.name


def generate(examples: Path, out: Path) -> list[str]:
    examples = Path(examples)
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        return [
            oblique(examples, out, tmp),
            camera(examples, out, tmp),
            uav(out, tmp),
            roi(examples, out),
            npy_small(out),
        ]
