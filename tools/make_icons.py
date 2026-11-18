"""Generates the RIVEM app icon.

    python -I tools/make_icons.py

Drawing: a river seen from above (green banks, blue water) crossed by a dashed yellow cross-section, with white
surface-velocity arrows whose length follows a velocity profile (slow at the banks, fast in the middle). No text,
opaque, readable at small sizes. Output (iOS asset catalog, kept outside the SwiftPM sources, wired in
ios/project.yml):
  - ios/AppIcon/Assets.xcassets/AppIcon.appiconset/AppIcon.png (1024 x 1024, RGB, no transparency)
  - the two Contents.json files
"""
import json
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZE = 1024
SS = 4  # supersampling for smooth edges

BANK = (46, 125, 50)
BANK_DARK = (38, 112, 43)
WATER_DEEP = (13, 71, 161)
WATER_LIGHT = (66, 165, 245)
SECTION = (255, 213, 79)
ARROW = (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def river_edges(y, s):
    """Left and right water edges at height y (pixels of the supersampled canvas): a gentle meander."""
    centre = s * (0.5 + 0.07 * math.sin(2 * math.pi * (y / s) * 0.9 + 0.6))
    half = s * 0.30
    return centre - half, centre + half


def render():
    s = SIZE * SS
    img = Image.new("RGB", (s, s), BANK)
    d = ImageDraw.Draw(img)

    # Banks: subtle diagonal stripes of darker green.
    for k in range(-s, 2 * s, s // 9):
        d.polygon([(k, 0), (k + s // 20, 0), (k + s // 20 - s // 3, s), (k - s // 3, s)], fill=BANK_DARK)

    # Water: row by row, darker in the middle (deeper), lighter near the edges.
    for y in range(s):
        x0, x1 = river_edges(y, s)
        width = x1 - x0
        steps = 24
        for k in range(steps):
            t0 = k / steps
            t1 = (k + 1) / steps
            mid = (t0 + t1) / 2
            depth = math.sin(math.pi * mid)  # 0 at the banks, 1 in the middle
            colour = lerp(WATER_LIGHT, WATER_DEEP, depth ** 0.8)
            d.line([(x0 + t0 * width, y), (x0 + t1 * width + 1, y)], fill=colour)

    # Cross-section (dashed yellow line across the river).
    ys = int(s * 0.62)
    x0, x1 = river_edges(ys, s)
    xa, xb = x0 - s * 0.06, x1 + s * 0.06
    dash = s * 0.045
    x = xa
    while x < xb:
        d.line([(x, ys), (min(x + dash, xb), ys)], fill=SECTION, width=int(s * 0.016))
        x += dash * 1.7
    for xe in (xa, xb):
        r = s * 0.022
        d.ellipse([xe - r, ys - r, xe + r, ys + r], fill=SECTION)

    # Surface-velocity arrows along the section, pointing downstream (up), longer in the middle.
    n = 5
    for i in range(n):
        t = (i + 0.5) / n
        xc = x0 + t * (x1 - x0)
        speed = math.sin(math.pi * t) ** 0.6
        length = s * (0.10 + 0.26 * speed)
        top = ys - length
        w = s * 0.022
        head = s * 0.075
        d.line([(xc, ys - s * 0.03), (xc, top + head * 0.6)], fill=ARROW, width=int(w))
        d.polygon([(xc, top), (xc - head * 0.62, top + head), (xc + head * 0.62, top + head)], fill=ARROW)

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def ios():
    catalog = os.path.join(ROOT, "ios", "AppIcon", "Assets.xcassets")
    iconset = os.path.join(catalog, "AppIcon.appiconset")
    os.makedirs(iconset, exist_ok=True)
    render().save(os.path.join(iconset, "AppIcon.png"), optimize=True)
    info = {"author": "xcode", "version": 1}
    with open(os.path.join(catalog, "Contents.json"), "w", encoding="utf-8") as f:
        json.dump({"info": info}, f, indent=2)
        f.write("\n")
    contents = {
        "images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": info,
    }
    with open(os.path.join(iconset, "Contents.json"), "w", encoding="utf-8") as f:
        json.dump(contents, f, indent=2)
        f.write("\n")
    print("wrote", os.path.join(iconset, "AppIcon.png"))


if __name__ == "__main__":
    ios()
