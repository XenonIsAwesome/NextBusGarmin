#!/usr/bin/env python3
"""Rebuilds the watch logo PNGs from tools/logos/*.svg, pre-scaled per screen size
(drawScaledBitmap is not available on the target devices).

Needs: pip install resvg-py pillow   (resvg handles the mask-heavy NTT file that cairosvg cannot)
Heights are 11% of the screen height, matching Operators.LOGO_HEIGHT_PERCENT.
"""
import io, os
import resvg_py
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
NAMES = ["egged", "egged_taavura", "dan", "ntt", "nateev", "metropoline", "superbus", "kavim"]
# screen size -> resource directory under watch/ (260 is the default set)
SIZES = {
    218: "resources-round-218x218/logos",   # fr255s, fr255sm
    260: "resources/logos",                 # fr255, fr255m (default)
    390: "resources-round-390x390/logos",   # fr165, fr165m
}

for screen, outdir in SIZES.items():
    h = screen * 11 // 100
    path = os.path.join(ROOT, "watch", outdir)
    os.makedirs(path, exist_ok=True)
    for name in NAMES:
        svg = os.path.join(ROOT, "tools", "logos", name + ".svg")
        im = Image.open(io.BytesIO(bytes(resvg_py.svg_to_bytes(svg_path=svg, height=h * 6)))).convert("RGBA")
        im = im.crop(im.getbbox())
        im = im.resize((max(1, round(im.width * h / im.height)), h), Image.LANCZOS)
        im.save(os.path.join(path, name + ".png"), optimize=True)
        print(screen, name, im.size)

# Phone app: one PNG per operator, 96 px high, as drawable-nodpi (shown at ~28 dp, so sharp up to 3.4x density).
ANDROID_RES = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable-nodpi")
os.makedirs(ANDROID_RES, exist_ok=True)
for name in NAMES:
    svg = os.path.join(ROOT, "tools", "logos", name + ".svg")
    im = Image.open(io.BytesIO(bytes(resvg_py.svg_to_bytes(svg_path=svg, height=96 * 6)))).convert("RGBA")
    im = im.crop(im.getbbox())
    im = im.resize((max(1, round(im.width * 96 / im.height)), 96), Image.LANCZOS)
    im.save(os.path.join(ANDROID_RES, "logo_" + name + ".png"), optimize=True)
    print("android", name, im.size)
