#!/usr/bin/env python3
"""Rasterize droidhost-social-preview.svg geometry to PNG (1280x640).

Reads tools/wordmark-kit.json emitted by wordmark_outline.py and draws the
same geometry with PIL at 2x supersampling. Glyph holes stay transparent via
a separate wordmark layer, so rings behind the text are not clipped.
Requires: pillow.
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ARTWORK = Path(__file__).resolve().parent.parent
S = 2


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shoelace(pts):
    a = 0.0
    for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
        a += x1 * y2 - x2 * y1
    return a / 2


def main():
    kit = json.loads((ARTWORK / "tools" / "wordmark-kit.json").read_text(encoding="utf-8"))
    W, H = kit["canvas"]
    base = Image.new("RGBA", (W * S, H * S), hex_rgb(kit["bg"]) + (255,))

    rings = Image.new("RGBA", base.size, (0, 0, 0, 0))
    rd = ImageDraw.Draw(rings)
    for r in kit["rings"]:
        c, a = hex_rgb(r["color"]), round(r["opacity"] * 255)
        x0, y0 = (r["cx"] - r["r"]) * S, (r["cy"] - r["r"]) * S
        x1, y1 = (r["cx"] + r["r"]) * S, (r["cy"] + r["r"]) * S
        rd.ellipse([x0, y0, x1, y1], outline=c + (a,), width=max(1, round(r["w"] * S)))
    base = Image.alpha_composite(base, rings)

    d = ImageDraw.Draw(base)
    m = kit["mark"]
    pts = [(x * S, y * S) for x, y in m["frame_points"]]
    # PIL's wide-line joints seam at shallow angles; stamp dense circles instead
    # (equivalent to an SVG round-cap/round-join stroke).
    cr = m["cap_r"] * S
    dense = [pts[0]]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dist = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
        n = max(1, int(dist / (cr / 3)))
        for k in range(1, n + 1):
            t = k / n
            dense.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
    ink = hex_rgb(m["stroke"]) + (255,)
    for x, y in dense:
        d.ellipse([x - cr, y - cr, x + cr, y + cr], fill=ink)
    c = m["core"]
    d.rounded_rectangle([c["x"] * S, c["y"] * S, (c["x"] + c["w"]) * S, (c["y"] + c["h"]) * S],
                        radius=c["rx"] * S, fill=hex_rgb(c["fill"]) + (255,))

    wm = Image.new("RGBA", base.size, (0, 0, 0, 0))
    wd = ImageDraw.Draw(wm)
    for group in kit["wordmark"]:
        fill = hex_rgb(group["fill"]) + (255,)
        contours = sorted(group["contours"], key=lambda p: abs(shoelace(p)), reverse=True)
        for poly in contours:
            scaled = [(x * S, y * S) for x, y in poly]
            # TrueType outers are CW in font y-up coords -> positive shoelace after y-flip.
            wd.polygon(scaled, fill=fill if shoelace(poly) > 0 else (0, 0, 0, 0))
    base = Image.alpha_composite(base, wm)

    out = base.resize((W, H), Image.LANCZOS).convert("RGB")
    dest = ARTWORK / "droidhost-social-preview.png"
    out.save(dest, optimize=True)
    print(f"written: {dest} ({W}x{H})")


if __name__ == "__main__":
    main()
