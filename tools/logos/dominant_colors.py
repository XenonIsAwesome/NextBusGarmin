#!/usr/bin/env python3
"""Prints each logo's dominant colour (the most frequent rendered colour, not an average).

Needs: pip install resvg-py pillow
Neutral pixels (greys, white, black) are ignored: they are not an operator's identity
colour and the line number is drawn on black. A colour family counts as dominant if it
covers >= 20% of the logo's opaque pixels; when a logo has several, the one furthest
from the other operators' colours is the pick (see docs/README.md, Operators.mc).
"""
import collections, colorsys, io, os, sys
import resvg_py
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
LOGOS = {3: "egged", 4: "egged_taavura", 5: "dan", 7: "ntt", 14: "nateev",
         15: "metropoline", 16: "superbus", 18: "kavim"}


def families(name):
    im = Image.open(io.BytesIO(bytes(resvg_py.svg_to_bytes(svg_path=os.path.join(HERE, name + ".svg"), height=200)))).convert("RGBA")
    px = [p for p in im.getdata() if p[3] > 200]
    bins = collections.defaultdict(collections.Counter)
    for r, g, b, _ in px:
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if s < 0.25 or v < 0.15:
            continue
        bins[int(h * 360) // 30 % 12][(r, g, b)] += 1
    fams = [(sum(c.values()) / len(px), *c.most_common(1)[0]) for c in bins.values()]
    # Bins holding nearly the same colour (e.g. two reds across the 0/360 seam) are one family;
    # its colour is the single most frequent exact colour among them (never an average).
    fams.sort(reverse=True)
    merged = []
    for share, col, count in fams:
        for m in merged:
            if sum((a - b) ** 2 for a, b in zip(col, m[1])) < 60 ** 2:
                m[0] += share
                if count > m[2]:
                    m[1], m[2] = col, count
                break
        else:
            merged.append([share, col, count])
    return [(m[0], m[1]) for m in merged if m[0] >= 0.20]


def lab(c):
    def f(v):
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = map(f, c)
    x, y, z = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, 0.2126 * r + 0.7152 * g + 0.0722 * b, (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    t = lambda v: v ** (1 / 3) if v > 0.008856 else 7.787 * v + 16 / 116
    return 116 * t(y) - 16, 500 * (t(x) - t(y)), 200 * (t(y) - t(z))


def dist(a, b):
    return sum((p - q) ** 2 for p, q in zip(lab(a), lab(b))) ** 0.5


def picks():
    """operator id -> (colour, note). Single-dominant logos first; a logo with several
    dominant colours then takes the one furthest (CIE Lab) from every other operator's pick."""
    fam = {oid: families(name) for oid, name in LOGOS.items()}
    chosen = {oid: (f[0][1], "") for oid, f in fam.items() if len(f) == 1}
    for oid, f in fam.items():
        if len(f) > 1:
            others = [c for c, _ in chosen.values()]
            best = max(f, key=lambda sc: min(dist(sc[1], o) for o in others))
            chosen[oid] = (best[1], "one of %d dominant colours, furthest from the other operators" % len(f))
    return chosen


if __name__ == "__main__" and "--table" not in sys.argv:
    chosen = picks()
    for oid, name in LOGOS.items():
        print(oid, name, [(round(s * 100), "#%02X%02X%02X" % c) for s, c in families(name)],
              "->", "#%02X%02X%02X" % chosen[oid][0], chosen[oid][1])


# The fr255/fr255s displays show only 4 levels per channel (64 colours); anything else is
# snapped by the system, often to a different hue (light green -> teal). This returns the
# palette colour perceptually closest to `c`, which Operators.mc uses on those screens.
def mip_snap(c):
    levels = (0x00, 0x55, 0xAA, 0xFF)
    return min(((r, g, b) for r in levels for g in levels for b in levels), key=lambda p: dist(p, c))


def table():
    """(id, true colour, 64-colour match) for every operator in Operators.mc."""
    chosen = picks()
    rest = {6: 0x8A5A2B, 8: 0xC0392B, 10: 0x1ABC9C, 20: 0x16A085, 21: 0x5D6D7E, 23: 0x884EA0, 24: 0x7D6608}
    out = {oid: c for oid, (c, _) in chosen.items()}
    out.update({oid: ((v >> 16) & 255, (v >> 8) & 255, v & 255) for oid, v in rest.items()})
    return {oid: (c, mip_snap(c)) for oid, c in sorted(out.items())}


if __name__ == "__main__" and "--table" in sys.argv:
    for oid, (c, m) in table().items():
        print(oid, "0x%02X%02X%02X" % c, "0x%02X%02X%02X" % m)
