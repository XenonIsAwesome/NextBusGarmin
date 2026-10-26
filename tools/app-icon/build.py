#!/usr/bin/env python3
"""Builds every app-icon asset from tools/app-icon/app_icon.svg.

    pip install resvg-py pillow
    python3 tools/app-icon/build.py

Writes: store_icon_500.png (Connect IQ Store), the watch launcher icon SVG, and the Android adaptive icon
(foreground PNG; the background colour is in res/values/ic_launcher_background.xml).
"""
import io, os, re
import resvg_py
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
SRC = os.path.join(HERE, "app_icon.svg")
svg = open(SRC).read()


def render(text, size):
    return Image.open(io.BytesIO(bytes(resvg_py.svg_to_bytes(svg_string=text, width=size, height=size)))).convert("RGBA")


# Connect IQ Store icon, 500x500.
render(svg, 500).save(os.path.join(HERE, "store_icon_500.png"), optimize=True)

# Watch launcher icons: 40 px for the fr255 family (default resources), 54 px for the fr165 family
# (resources-round-390x390). Only plain shapes are used, which the Connect IQ resource compiler handles.
for folder, px in (("resources", 40), ("resources-round-390x390", 54)):
    out = os.path.join(ROOT, "watch", folder, "drawables")
    os.makedirs(out, exist_ok=True)
    text = re.sub(r'width="512" height="512"', f'width="{px}" height="{px}"', svg, count=1)
    open(os.path.join(out, "launcher_icon.svg"), "w").write(text)

# Android adaptive icon: artwork without the rounded square, inside the 66% safe zone of a 432 px layer.
art = re.sub(r'\s*<rect width="512" height="512" rx="112" fill="#14407F"/>', "", svg, count=1)
layer = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
inner = render(art, 340)
layer.paste(inner, ((432 - 340) // 2, (432 - 340) // 2 + 6), inner)
res = os.path.join(ROOT, "android", "app", "src", "main", "res")
os.makedirs(os.path.join(res, "drawable-nodpi"), exist_ok=True)
layer.save(os.path.join(res, "drawable-nodpi", "ic_launcher_foreground.png"), optimize=True)
