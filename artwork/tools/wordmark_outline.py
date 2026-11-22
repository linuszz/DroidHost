#!/usr/bin/env python3
"""Outline the DroidHost wordmark from the bundled Space Grotesk variable font.

Reproducible source for the outlined wordmark SVGs (horizontal / vertical,
color / mono), the 1280x360 banners aligned to the upstream layout, and the
1280x640 social-preview SVG. Also emits wordmark-kit.json: flattened polygon
geometry for render_social_preview.py (PIL rasterizer).

Layout matches WordmarkArt.kt: two independent runs ("Droid" wght 400,
"Host" wght 700), cap-height based sizing, shared baseline, no cross-run
kerning. Requires: fonttools.
"""
import json
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ARTWORK = Path(__file__).resolve().parent.parent
TTF = ARTWORK.parent / "app" / "src" / "main" / "res" / "font" / "space_grotesk_variable.ttf"

INK = "#F5F7FA"
MINT = "#00E5A0"
BG = "#0B0E14"

FRAME_PATH = ("M209 137 L209 180 Q209 209 180 209 L60 209 Q31 209 31 180 "
              "L31 60 Q31 31 60 31 L180 31 Q209 31 209 60 L209 103")

# Upstream droiddeck-banner-dark.svg layout constants (1280x360).
BANNER_RING_C = (289.75, 182.36)
BANNER_RINGS = [(155.76, 12, 0.091, 2.4, 0.260),
                (241.9, 9.5, 0.052, 1.9, 0.150),
                (348.1, 7, 0.028, 1.4, 0.080)]
BANNER_MARK_TF = (218.95, 111.56, 0.59)  # A1 outer 200 -> 118px, bottom = baseline
BANNER_WORD_X = 361.45
BANNER_BASELINE = 241.36
BANNER_CAP = 85.0

SOCIAL_MARK_C = (640.0, 245.0)
SOCIAL_MARK_SCALE = 0.95
SOCIAL_RINGS = [(150, 2, 0.10), (235, 1.5, 0.07), (330, 1, 0.05)]
SOCIAL_CAP = 64.0
SOCIAL_BASELINE = 489.0


def instance(wght):
    font = TTFont(TTF)
    instantiateVariableFont(font, {"wght": wght}, inplace=True)
    return font


def cap_height(font):
    v = getattr(font["OS/2"], "sCapHeight", 0)
    if v:
        return float(v)
    gs = font.getGlyphSet()
    bp = BoundsPen(gs)
    gs[font.getBestCmap()[ord("H")]].draw(bp)
    return bp.bounds[3]


def pairpos_subtables(font):
    if "GPOS" not in font:
        return
    for lk in font["GPOS"].table.LookupList.Lookup:
        for st in lk.SubTable:
            if lk.LookupType == 9:
                if st.ExtensionLookupType == 2:
                    yield st.ExtSubTable
            elif lk.LookupType == 2:
                yield st


def kern_map(font):
    pairs = {}
    for st in pairpos_subtables(font):
        if st.Format == 1:
            for i, left in enumerate(st.Coverage.glyphs):
                for pvr in st.PairSet[i].PairValueRecord:
                    adj = getattr(pvr.Value1, "XAdvance", 0)
                    if adj:
                        pairs[(left, pvr.SecondGlyph)] = adj
        elif st.Format == 2:
            lefts, rights = {}, {}
            for g in st.Coverage.glyphs:
                lefts.setdefault(st.ClassDef1.classDefs.get(g, 0), []).append(g)
            for g, c in st.ClassDef2.classDefs.items():
                rights.setdefault(c, []).append(g)
            for c1, rec1 in enumerate(st.Class1Record):
                for c2, rec2 in enumerate(rec1.Class2Record):
                    adj = getattr(rec2.Value1, "XAdvance", 0)
                    if not adj:
                        continue
                    for lg in lefts.get(c1, ()):
                        for rg in rights.get(c2, ()):
                            pairs[(lg, rg)] = adj
    return pairs


def flatten_q(p0, off, p1, steps=24):
    pts = []
    for k in range(1, steps + 1):
        t = k / steps
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * off[0] + t * t * p1[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * off[1] + t * t * p1[1]
        pts.append((x, y))
    return pts


def flatten_contours(rec):
    contours, cur = [], None
    for op, args in rec.value:
        if op == "moveTo":
            cur = [args[0]]
        elif op == "lineTo":
            cur.append(args[0])
        elif op == "qCurveTo":
            if args[-1] is None:
                raise ValueError("all-offcurve closed contour not supported")
            p0, offs, end = cur[-1], args[:-1], args[-1]
            for i, off in enumerate(offs):
                nxt = offs[i + 1] if i + 1 < len(offs) else end
                e = ((off[0] + nxt[0]) / 2, (off[1] + nxt[1]) / 2) if i + 1 < len(offs) else end
                cur.extend(flatten_q(p0, off, e))
                p0 = e
        elif op == "curveTo":
            p0, c1, c2, p1 = cur[-1], args[0], args[1], args[2]
            for k in range(1, 25):
                t = k / 24
                u = 1 - t
                cur.append((u ** 3 * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * p1[0],
                            u ** 3 * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * p1[1]))
        elif op == "closePath":
            if cur:
                contours.append(cur)
                cur = None
    if cur:
        contours.append(cur)
    return contours


def layout_run(font, text, cap_px, x0, baseline):
    """One text run in a single weight instance. Returns (svg_d, contours, width)."""
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]
    s = cap_px / cap_height(font)
    kern = kern_map(font)
    x, prev = x0, None
    parts, contours = [], []
    for ch in text:
        g = cmap[ord(ch)]
        if prev is not None:
            x += kern.get((prev, g), 0) * s
        sp = SVGPathPen(gs)
        gs[g].draw(TransformPen(sp, (s, 0, 0, -s, x, baseline)))
        parts.append(sp.getCommands())
        rp = DecomposingRecordingPen(gs)
        gs[g].draw(rp)
        for c in flatten_contours(rp):
            contours.append([(round(x + px * s, 2), round(baseline - py * s, 2)) for px, py in c])
        x += hmtx[g][0] * s
        prev = g
    return "".join(parts), contours, x - x0


def wordmark_runs(cap_px, x0, baseline, reg, bold):
    d1, c1, w1 = layout_run(reg, "Droid", cap_px, x0, baseline)
    d2, c2, w2 = layout_run(bold, "Host", cap_px, x0 + w1, baseline)
    return d1, d2, c1, c2, w1 + w2


def svg_head(w, h, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" role="img" aria-label="{title}">\n<title>{title}</title>\n')


def write_wordmarks(reg, bold):
    cap, pad, gap = 100.0, 20.0, 26.0
    d1, d2, _, _, w = wordmark_runs(cap, pad, pad + cap, reg, bold)
    W, H = round(w + 2 * pad, 2), round(cap + 2 * pad, 2)
    head = svg_head(W, H, "DroidHost wordmark")
    (ARTWORK / "droidhost-wordmark.svg").write_text(
        head + f'<path fill="{INK}" d="{d1}"/>\n<path fill="{MINT}" d="{d2}"/>\n</svg>\n', encoding="utf-8")
    (ARTWORK / "droidhost-wordmark-mono.svg").write_text(
        head + f'<path fill="currentColor" d="{d1}{d2}"/>\n</svg>\n', encoding="utf-8")

    _, _, w1 = layout_run(reg, "Droid", cap, 0, 0)[:3]
    _, _, w2 = layout_run(bold, "Host", cap, 0, 0)[:3]
    inner = max(w1, w2)
    Wv = round(inner + 2 * pad, 2)
    Hv = round(2 * cap + gap + 2 * pad, 2)
    b1, b2 = pad + cap, pad + cap + gap + cap
    v1, _, _ = layout_run(reg, "Droid", cap, pad + (inner - w1) / 2, b1)[:3]
    v2, _, _ = layout_run(bold, "Host", cap, pad + (inner - w2) / 2, b2)[:3]
    headv = svg_head(Wv, Hv, "DroidHost wordmark, stacked")
    (ARTWORK / "droidhost-wordmark-vertical.svg").write_text(
        headv + f'<path fill="{INK}" d="{v1}"/>\n<path fill="{MINT}" d="{v2}"/>\n</svg>\n', encoding="utf-8")
    (ARTWORK / "droidhost-wordmark-vertical-mono.svg").write_text(
        headv + f'<path fill="currentColor" d="{v1}{v2}"/>\n</svg>\n', encoding="utf-8")
    return w


def mark_group(tf, frame_color):
    tx, ty, s = tf
    return (f'<g transform="translate({tx} {ty}) scale({s})">\n'
            f'<path d="{FRAME_PATH}" fill="none" stroke="{frame_color}" stroke-width="22" stroke-linecap="round"/>\n'
            f'<rect x="74" y="74" width="92" height="92" rx="23" fill="{MINT}"/>\n</g>\n')


def rings_svg(center, specs, color):
    cx, cy = center
    out = []
    for r, w1, o1, w2, o2 in specs:
        out.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{color}" stroke-opacity="{o1}" stroke-width="{w1}"/>')
        out.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{color}" stroke-opacity="{o2}" stroke-width="{w2}"/>')
    return "".join(out)


def banner_svg(theme, reg, bold):
    light = theme == "light"
    stops = ('<stop offset="0" stop-color="#FFFFFF"/><stop offset="0.42" stop-color="#F3F6F9"/>'
             '<stop offset="1" stop-color="#E5EAF0"/>') if light else (
             '<stop offset="0" stop-color="#161D26"/><stop offset="0.42" stop-color="#0B0E14"/>'
             '<stop offset="1" stop-color="#06080C"/>')
    ring_ops = [(r, w1, o1 * 1.8, w2, o2 * 1.6) for r, w1, o1, w2, o2 in BANNER_RINGS] if light else BANNER_RINGS
    glow_op = 0.14 if light else 0.17
    frame = BG if light else INK
    host_color = BG if light else MINT
    border = "rgba(11,14,20,0.10)" if light else "rgba(255,255,255,0.07)"
    d1, d2, _, _, w = wordmark_runs(BANNER_CAP, BANNER_WORD_X, BANNER_BASELINE, reg, bold)
    if BANNER_WORD_X + w > 1230:
        raise ValueError(f"banner wordmark too wide: {w:.1f}px")
    cx, cy = BANNER_RING_C
    svg = svg_head(1280, 360, f"DroidHost banner ({theme})")
    svg += ('<defs>\n'
            f'<radialGradient id="bg" cx="0.36" cy="0.46" r="0.95" gradientTransform="translate(0.36 0.46) scale(0.48 1) translate(-0.36 -0.46)">{stops}</radialGradient>\n'
            f'<radialGradient id="glow"><stop offset="0.35" stop-color="{MINT}" stop-opacity="{glow_op}"/>'
            f'<stop offset="1" stop-color="{MINT}" stop-opacity="0"/></radialGradient>\n'
            '<clipPath id="card"><rect width="1280" height="360" rx="26"/></clipPath>\n'
            '</defs>\n<g clip-path="url(#card)">\n'
            '<rect width="1280" height="360" fill="url(#bg)"/>\n')
    svg += rings_svg(BANNER_RING_C, ring_ops, MINT)
    svg += f'<circle cx="{cx}" cy="{cy}" r="123.9" fill="url(#glow)"/>\n'
    svg += mark_group(BANNER_MARK_TF, frame)
    svg += f'<path fill="{frame}" d="{d1}"/>\n<path fill="{host_color}" d="{d2}"/>\n'
    svg += '</g>\n'
    svg += f'<rect x="0.5" y="0.5" width="1279" height="359" rx="25.5" fill="none" stroke="{border}"/>\n</svg>\n'
    (ARTWORK / f"droidhost-banner-{theme}.svg").write_text(svg, encoding="utf-8")
    return w


def frame_polyline(tf):
    tx, ty, s = tf
    segs = [("M", (209, 137)), ("L", (209, 180)), ("Q", (209, 209), (180, 209)),
            ("L", (60, 209)), ("Q", (31, 209), (31, 180)), ("L", (31, 60)),
            ("Q", (31, 31), (60, 31)), ("L", (180, 31)), ("Q", (209, 31), (209, 60)),
            ("L", (209, 103))]
    pts, cur = [], None
    for seg in segs:
        if seg[0] == "M":
            cur = seg[1]
            pts.append(cur)
        elif seg[0] == "L":
            cur = seg[1]
            pts.append(cur)
        else:
            flat = flatten_q(cur, seg[1], seg[2])
            pts.extend(flat)
            cur = seg[2]
    return [(round(tx + x * s, 2), round(ty + y * s, 2)) for x, y in pts]


def social_svg(reg, bold):
    d1, d2, c1, c2, w = wordmark_runs(SOCIAL_CAP, 640 - 0, SOCIAL_BASELINE, reg, bold)
    x0 = 640 - w / 2
    d1, d2, c1, c2, w = wordmark_runs(SOCIAL_CAP, x0, SOCIAL_BASELINE, reg, bold)
    tx = SOCIAL_MARK_C[0] - 120 * SOCIAL_MARK_SCALE
    ty = SOCIAL_MARK_C[1] - 120 * SOCIAL_MARK_SCALE
    tf = (tx, ty, SOCIAL_MARK_SCALE)
    svg = svg_head(1280, 640, "DroidHost social preview")
    svg += f'<rect width="1280" height="640" fill="{BG}"/>\n'
    cx, cy = SOCIAL_MARK_C
    for r, wd, op in SOCIAL_RINGS:
        svg += f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{MINT}" stroke-opacity="{op}" stroke-width="{wd}"/>\n'
    svg += mark_group(tf, INK)
    svg += f'<path fill="{INK}" d="{d1}"/>\n<path fill="{MINT}" d="{d2}"/>\n</svg>\n'
    (ARTWORK / "droidhost-social-preview.svg").write_text(svg, encoding="utf-8")

    s = SOCIAL_MARK_SCALE
    kit = {
        "canvas": [1280, 640], "bg": BG,
        "rings": [{"cx": cx, "cy": cy, "r": r, "w": wd, "opacity": op, "color": MINT} for r, wd, op in SOCIAL_RINGS],
        "mark": {
            "frame_points": frame_polyline(tf), "stroke": INK,
            "stroke_w": 22 * s, "cap_r": 11 * s,
            "core": {"x": tx + 74 * s, "y": ty + 74 * s, "w": 92 * s, "h": 92 * s, "rx": 23 * s, "fill": MINT},
        },
        "wordmark": [{"fill": INK, "contours": c1}, {"fill": MINT, "contours": c2}],
    }
    (ARTWORK / "tools" / "wordmark-kit.json").write_text(json.dumps(kit), encoding="utf-8")
    return w


def main():
    reg, bold = instance(400), instance(700)
    print(f"capHeight: reg={cap_height(reg):.0f} bold={cap_height(bold):.0f} upm={reg['head'].unitsPerEm}")
    w_h = write_wordmarks(reg, bold)
    w_b = banner_svg("dark", reg, bold)
    banner_svg("light", reg, bold)
    w_s = social_svg(reg, bold)
    print(f"wordmark width: horizontal(cap100)={w_h:.1f} banner(cap85)={w_b:.1f} social(cap64)={w_s:.1f}")
    print("written: wordmark x4, banner x2, social-preview.svg, tools/wordmark-kit.json")


if __name__ == "__main__":
    main()
