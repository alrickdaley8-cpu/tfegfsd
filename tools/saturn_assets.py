#!/usr/bin/env python3
"""
saturn_assets.py — procedural sprite + palette generator for the Saturn
IKEMEN GO character (original AI-generated stand-in artwork).

WHAT THIS DOES
--------------
1. Builds a fixed 256-color MASTER PALETTE (indexed, deterministic) so every
   generated sprite shares identical palette slots (this is what makes the
   engine-side palette remaps actually recolour the character).
2. Defines 4 palettes: default, "Gloom" (dark), "Void" (supernatural),
   "Elder Star" (boss).  Only accent ramps are recoloured; the silhouette and
   readability are preserved.
3. Renders every body/VFX frame procedurally (supersampled, then quantised to
   the master palette) and writes them out as indexed PNGs.
4. Writes a manifest.json listing group/image -> file, size, axis so the
   SFF builder and the validator can stay in sync with the .air file.

USAGE
-----
    python3 tools/saturn_assets.py            # generate sprites + manifest
    python3 tools/saturn_assets.py --preview  # also emit a contact sheet

Outputs (default):
    chars/Saturn/_build/sprites/<group>_<image>.png
    chars/Saturn/_build/manifest.json
    chars/Saturn/_build/preview.png   (only with --preview)

The generated images are ORIGINAL AI-assisted placeholder art inspired by a
large supernatural elder archetype.  They are NOT extracted from any existing
game.  See INSTALL.md / LIMITATIONS.md for the documented swap-in pipeline to
replace them with the author's own Saturn sprite.
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

# ----------------------------------------------------------------------------
# Canvas / coordinate conventions
# ----------------------------------------------------------------------------
# Body sprites share one fixed canvas so frames never jitter: the axis
# (the character's feet-centre) is identical for every body frame.
BODY_W, BODY_H = 128, 168
BODY_AXIS = (64, 160)
SS = 4  # supersampling factor

# ----------------------------------------------------------------------------
# MASTER PALETTE (indexed)
# ----------------------------------------------------------------------------
# Index layout is FIXED and shared by every sprite.  Only the accent ramps
# (aura/orb/trim/etc.) are recoloured by the alternate palettes.
PAL = {}  # name -> list of (r,g,b) shades (index 0 = transparent, filled first)

# (transparent slot is index 0)
PAL["none"] = [(0, 0, 0)]

# outline / darks
PAL["line"] = [(15, 11, 24), (30, 23, 42)]

# skin: pale lavender-grey elder
PAL["skin"] = [
    (212, 204, 220), (196, 188, 206), (178, 170, 190), (158, 150, 172),
    (138, 130, 154), (116, 108, 134), (96, 88, 114), (76, 68, 92),
    (57, 51, 70), (40, 35, 50),
]

# hair / beard white
PAL["beard"] = [
    (240, 238, 244), (226, 223, 232), (208, 205, 216), (188, 184, 198),
    (164, 160, 176), (138, 134, 152), (112, 108, 126), (86, 82, 98),
]

# robe: deep violet / near-black
PAL["robe"] = [
    (66, 52, 96), (54, 42, 80), (44, 34, 66), (36, 27, 54), (29, 21, 44),
    (23, 17, 36), (18, 13, 29), (13, 9, 22), (9, 6, 16), (6, 4, 11),
    (84, 68, 118), (106, 88, 142),
]

# robe trim: gold
PAL["trim"] = [
    (214, 178, 96), (196, 158, 80), (174, 136, 66), (150, 114, 52),
    (124, 92, 42), (98, 72, 34), (72, 52, 26), (48, 34, 18),
]

# horns: ivory
PAL["horn"] = [
    (224, 214, 196), (206, 194, 172), (184, 170, 146), (160, 146, 122),
    (134, 120, 98), (106, 94, 76), (78, 68, 54),
]

# eyes: crimson glow
PAL["eye"] = [
    (255, 120, 130), (238, 60, 74), (196, 30, 46), (150, 18, 30),
    (104, 10, 20), (60, 6, 12),
]

# aura: supernatural violet/magenta (recolour target)
PAL["aura"] = [
    (196, 120, 255), (178, 96, 244), (158, 72, 226), (136, 52, 200),
    (112, 38, 168), (88, 28, 134), (66, 20, 102), (46, 13, 72),
    (30, 8, 46), (18, 5, 28),
]

# orb: dark blood-red energy (recolour target)
PAL["orb"] = [
    (255, 120, 120), (226, 70, 70), (192, 40, 44), (158, 26, 30),
    (124, 18, 22), (92, 12, 16), (64, 8, 12), (40, 5, 8),
]

# impact flashes (white -> yellow -> orange)
PAL["impact"] = [
    (255, 255, 255), (255, 244, 200), (255, 220, 140), (255, 188, 84),
    (244, 150, 60), (224, 112, 44), (196, 80, 32), (160, 56, 24),
    (120, 38, 18), (82, 24, 12),
]

# smoke
PAL["smoke"] = [
    (120, 116, 132), (100, 96, 112), (82, 78, 94), (66, 62, 78),
    (50, 46, 62), (36, 33, 46), (24, 22, 32), (14, 13, 20),
]

# secondary accent (recolour target)
PAL["accent"] = [
    (150, 210, 255), (116, 178, 240), (84, 146, 220), (58, 116, 196),
    (40, 88, 162), (28, 64, 126), (18, 44, 90),
]


def build_master_palette():
    """Assemble the fixed master palette from the named ramps (index 0 = none)."""
    order = ["none", "line", "skin", "beard", "robe", "trim", "horn",
             "eye", "aura", "orb", "impact", "smoke", "accent"]
    pal = []
    ranges = {}
    for name in order:
        start = len(pal)
        pal.extend(PAL[name])
        ranges[name] = (start, len(pal) - 1)
    # pad to 256 with transparent black
    while len(pal) < 256:
        pal.append((0, 0, 0))
    return pal, ranges


MASTER, RANGES = build_master_palette()


def recolor(pal, ranges, edits):
    """Return a recoloured 256 palette. `edits` maps ramp name -> new shades
    (same length as the original ramp) or a function(ramp)->new ramp."""
    out = list(pal)
    for name, fn in edits.items():
        s, e = ranges[name]
        ramp = pal[s:e + 1]
        if callable(fn):
            new = fn(ramp)
        else:
            new = fn
        assert len(new) == len(ramp), f"recolor {name} length mismatch"
        for i, c in enumerate(new):
            out[s + i] = c
    return out


def _shift(ramp, hue_dx, sat_mul=1.0, val_mul=1.0):
    """Quick HSV tweak of a ramp (used to build the alternate palettes)."""
    import colorsys
    out = []
    for r, g, b in ramp:
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        h = (h + hue_dx) % 1.0
        s = min(1.0, s * sat_mul)
        v = min(1.0, v * val_mul)
        nr, ng, nb = colorsys.hsv_to_rgb(h, s, v)
        out.append((int(round(nr * 255)), int(round(ng * 255)), int(round(nb * 255))))
    return out


def make_palettes():
    """Return the four selectable palettes (default, gloom, void, elder star)."""
    default = list(MASTER)

    gloom = recolor(MASTER, RANGES, {
        "aura": _shift(PAL["aura"], 0.50, 0.9, 0.8),      # teal, dimmer
        "orb": _shift(PAL["orb"], 0.58, 1.0, 0.85),       # deep blue
        "accent": _shift(PAL["accent"], 0.0, 0.7, 0.9),
        "trim": _shift(PAL["trim"], 0.0, 0.35, 0.85),     # silver-ish
        "robe": [(int(r * 0.72), int(g * 0.72), int(b * 0.88)) for r, g, b in PAL["robe"]],
    })

    void = recolor(MASTER, RANGES, {
        "aura": _shift(PAL["aura"], 0.33, 1.1, 0.95),     # emerald
        "orb": _shift(PAL["orb"], 0.5, 1.1, 1.0),         # cyan
        "accent": _shift(PAL["accent"], 0.5, 1.2, 1.0),
        "skin": [(int(r * 0.9), int(g * 0.92), int(b * 1.02)) for r, g, b in PAL["skin"]],
        "eye": [(255, 180, 140), (255, 120, 80), (230, 70, 40), (190, 40, 20), (140, 22, 10), (80, 10, 6)],
    })

    elder = recolor(MASTER, RANGES, {
        "aura": _shift(PAL["aura"], 0.98, 1.1, 1.05),     # crimson
        "orb": [(255, 150, 60), (255, 110, 30), (238, 70, 14), (200, 44, 8), (156, 28, 6), (112, 16, 4), (76, 8, 3), (46, 4, 2)],
        "accent": _shift(PAL["accent"], 0.1, 1.2, 1.05),
        "trim": [(232, 200, 110), (220, 182, 92), (200, 158, 74), (178, 132, 58), (150, 104, 44), (120, 80, 34), (88, 56, 24), (58, 36, 16)],
        "eye": [(255, 140, 130), (255, 70, 60), (220, 30, 34), (176, 18, 22), (128, 10, 14), (70, 5, 8)],
    })

    return {
        "default": default,
        "gloom": gloom,
        "void": void,
        "elder": elder,
    }


PALETTES = make_palettes()

# ----------------------------------------------------------------------------
# Quantisation
# ----------------------------------------------------------------------------
def _color_key(c):
    r, g, b = c
    return (r * 3, g * 4, b * 2)  # green-weighted for perceptual-ish matching


_MASTER_KEYS = [_color_key(c) for c in MASTER]


def nearest_index(rgb):
    k = _color_key(rgb)
    best, bestd = 0, 1 << 30
    # most sprites are drawn with palette colours already; fast path
    for i, c in enumerate(MASTER):
        if c == rgb:
            return i
    for i, ck in enumerate(_MASTER_KEYS):
        dr, dg, db = (ck[0] - k[0]), (ck[1] - k[1]), (ck[2] - k[2])
        d = dr * dr + dg * dg + db * db
        if d < bestd:
            bestd, best = d, i
    return best


def quantize(img):
    """Convert an RGBA PIL image to indexed (P-mode) using the master palette."""
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    out = Image.new("P", (w, h), 0)
    outp = out.load()
    indices = bytearray(w * h)
    for y in range(h):
        row = y * w
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 10:
                indices[row + x] = 0
            else:
                # blend against the background only affects edges; we keep simple
                indices[row + x] = nearest_index((r, g, b))
    out.putdata(list(indices))
    # attach the master palette so previews look right
    flat = [v for c in MASTER for v in c] + [0] * (768 - len(MASTER) * 3)
    out.putpalette(flat)
    return out


# ----------------------------------------------------------------------------
# Drawing helpers (supersampled canvas coordinates)
# ----------------------------------------------------------------------------
def new_canvas(w, h):
    img = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    return img, d


def finish(img, w, h):
    img = img.resize((w, h), Image.LANCZOS)
    return quantize(img)


def rgba(name, alpha=255):
    r, g, b = PAL[name][0]
    return (r, g, b, alpha)


def shade(name, i, alpha=255):
    ramp = PAL[name]
    r, g, b = ramp[min(i, len(ramp) - 1)]
    return (r, g, b, alpha)


def capsule(d, p0, p1, width, color):
    """Thick rounded line between two points."""
    d.line([p0, p1], fill=color, width=int(width * SS))
    r = int(width * SS / 2)
    for p in (p0, p1):
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=color)


def radial_glow(img, cx, cy, r, color_ramp, strength=1.0):
    """Soft radial glow, drawn additively-ish via layered translucent ellipses."""
    d = ImageDraw.Draw(img)
    for i in range(len(color_ramp)):
        rr = r * (1.0 - i / len(color_ramp))
        if rr <= 0:
            break
        c = color_ramp[i]
        a = int(90 * strength * (1.0 - i / len(color_ramp)))
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(c[0], c[1], c[2], a))


# ----------------------------------------------------------------------------
# THE FIGURE
# ----------------------------------------------------------------------------
# All body drawing happens on a (BODY_W x BODY_H) canvas with the feet at
# y = BODY_H - 8 and the axis at BODY_AXIS.  Poses customise limbs/lean/aura.
def draw_arm(d, side, pose, t, lean=0.0):
    """Draw one arm. side: 'L' (far/back) or 'R' (near/front)."""
    back = side == "L"
    sx = 64 + (-1 if back else 1) * 18 + lean * 6
    sy = 52
    arm = pose.get("arm" + side, "down")  # down|jab|slash|upper|over|lance|hold|cross
    width = 10 if not back else 9
    col = shade("robe", 1) if back else shade("robe", 2)
    col2 = shade("robe", 4) if back else shade("robe", 5)

    # hand target per arm pose
    if arm == "down":
        hx, hy = sx + (0 if back else 6), sy + 44
        ex, ey = sx, sy + 22
    elif arm == "jab":
        hx, hy = sx + (52 if side == "R" else 30), sy + 10
        ex, ey = sx + (26 if side == "R" else 16), sy + 6
    elif arm == "slash":
        hx, hy = sx + (40 if side == "R" else -30), sy - 18
        ex, ey = sx + (22 if side == "R" else -18), sy - 8
    elif arm == "upper":
        hx, hy = sx + (14 if side == "R" else -10), sy - 34
        ex, ey = sx + (8 if side == "R" else -6), sy - 18
    elif arm == "over":
        hx, hy = sx + (20 if side == "R" else -20), sy - 40
        ex, ey = sx + (10 if side == "R" else -10), sy - 22
    elif arm == "lance":
        hx, hy = sx + (54 if side == "R" else 34), sy + 4
        ex, ey = sx + (28 if side == "R" else 18), sy + 2
    elif arm == "hold":
        hx, hy = sx + (24 if side == "R" else -20), sy + 6
        ex, ey = sx + (14 if side == "R" else -10), sy + 4
    else:  # cross / channel
        hx, hy = 64, sy + 12
        ex, ey = sx, sy + 8

    capsule(d, (sx, sy), (ex, ey), width, col)
    capsule(d, (ex, ey), (hx, hy), width, col2)
    # claw hand: 3 short fingers
    fang = [(-8, 4), (0, 8), (8, 4)]
    for fx, fy in fang:
        d.line([(hx, hy), (hx + fx, hy + fy)], fill=shade("skin", 4), width=int(3 * SS))
    # palm
    pr = int(width * SS * 0.8)
    d.ellipse([hx - pr, hy - pr, hx + pr, hy + pr], fill=col2)


def draw_figure(img, pose, t=0.0):
    """Render the Saturn figure into the (already supersampled) canvas."""
    d = ImageDraw.Draw(img)
    w, h = BODY_W * SS, BODY_H * SS

    air = pose.get("air", 0)
    crouch = pose.get("crouch", 0)
    lean = pose.get("lean", 0.0)
    aura = pose.get("aura", 1.0)
    breathe = math.sin(t * math.tau) * pose.get("breathe", 1.0)

    # vertical offset: crouch squashes, air lifts
    if air:
        dy = -34
    elif crouch:
        dy = 26
    else:
        dy = 0
    dy += breathe

    # -- aura --
    if aura > 0.02:
        cx, cy = 64 * SS, (86 + dy) * SS
        radial_glow(img, cx, cy, 74 * SS, PAL["aura"], strength=aura)

    # -- spider-leg hint behind robe (dark tentacles) --
    if not crouch and pose.get("legs", 1):
        legs = [(-34, 150, -48, 158), (-24, 152, -30, 160), (24, 152, 30, 160), (34, 150, 48, 158)]
        for x1, y1, x2, y2 in legs:
            d.line([((64 + x1) * SS, (y1 + dy * 0.3) * SS),
                    ((64 + x2) * SS, (y2 + dy * 0.3) * SS)],
                   fill=shade("robe", 8), width=int(5 * SS))

    # -- robe body --
    top_y = 46 + dy
    bot_y = 156 + dy * 0.4
    robe_pts = [
        (64 - 26, top_y), (64 - 20, top_y - 6), (64 - 8, top_y),
        (64 + 8, top_y), (64 + 20, top_y - 6), (64 + 26, top_y),
        (64 + 34, top_y + 26), (64 + 30, bot_y - 6), (64 + 18, bot_y),
        (64 - 18, bot_y), (64 - 30, bot_y - 6), (64 - 34, top_y + 26),
    ]
    d.polygon([(x * SS, y * SS) for x, y in robe_pts], fill=shade("robe", 3))
    # robe shading (side)
    d.polygon([(x * SS, y * SS) for x, y in [
        (64 + 8, top_y), (64 + 26, top_y), (64 + 34, top_y + 26),
        (64 + 30, bot_y - 6), (64 + 18, bot_y), (64 + 6, bot_y),
    ]], fill=shade("robe", 5))
    # robe highlight
    d.arc([(40) * SS, (top_y - 8) * SS, (88) * SS, (top_y + 30) * SS],
          start=180, end=300, fill=shade("robe", 11), width=int(2 * SS))
    # gold trim hem
    d.line([(46 * SS, (bot_y - 4) * SS), (82 * SS, (bot_y - 4) * SS)],
           fill=shade("trim", 2), width=int(3 * SS))
    d.line([(36 * SS, (top_y + 12) * SS), (92 * SS, (top_y + 12) * SS)],
           fill=shade("trim", 4), width=int(2 * SS))
    # center emblem (the five-elders star motif — 5-pointed)
    ecx, ecy, er = 64 * SS, (top_y + 52) * SS, 13 * SS
    d.ellipse([ecx - er, ecy - er, ecx + er, ecy + er], outline=shade("trim", 1), width=int(2 * SS))
    d.ellipse([ecx - er // 3, ecy - er // 3, ecx + er // 3, ecy + er // 3], fill=shade("trim", 1))

    # -- shoulders / collar --
    d.ellipse([(46) * SS, (top_y - 8) * SS, (82) * SS, (top_y + 12) * SS], fill=shade("robe", 2))
    d.polygon([(52 * SS, (top_y - 10) * SS), (76 * SS, (top_y - 10) * SS),
               (72 * SS, (top_y + 2) * SS), (56 * SS, (top_y + 2) * SS)],
              fill=shade("robe", 4))

    # -- head --
    hx, hy = 64 * SS, (top_y - 22) * SS
    hr = 21 * SS
    d.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=shade("skin", 1))
    # head shading (top-right light)
    d.ellipse([hx - hr, hy - hr, hx + hr * 0.6, hy + hr * 0.4], fill=shade("skin", 2))
    # forehead dome highlight
    d.ellipse([hx - hr * 0.5, hy - hr * 0.8, hx + hr * 0.5, hy - hr * 0.1], fill=shade("skin", 0))

    # -- horns (short, curled back) --
    for sgn in (-1, 1):
        bx, by = hx + sgn * 10 * SS, hy - 14 * SS
        d.polygon([(bx - 5 * SS, by), (bx + 5 * SS, by),
                   (bx + sgn * 14 * SS, by - 10 * SS)],
                  fill=shade("horn", 2))
        d.line([(bx, by), (bx + sgn * 12 * SS, by - 10 * SS)], fill=shade("horn", 0), width=int(2 * SS))

    # -- beard / mustache --
    btop = hy + hr * 0.55
    d.ellipse([hx - 16 * SS, btop, hx + 16 * SS, (top_y + 34) * SS], fill=shade("beard", 1))
    d.ellipse([hx - 12 * SS, btop, hx + 12 * SS, (top_y + 30) * SS], fill=shade("beard", 0))
    # beard strands
    for sx_ in (-10, -4, 4, 10):
        d.line([(hx + sx_ * SS, btop + 4 * SS), (hx + sx_ * SS, (top_y + 32) * SS)],
               fill=shade("beard", 3), width=int(1 * SS))
    # mustache
    d.arc([hx - 18 * SS, btop - 4 * SS, hx + 18 * SS, btop + 10 * SS],
          start=20, end=160, fill=shade("beard", 1), width=int(3 * SS))

    # -- eyes (glowing supernatural) --
    eye_y = hy + 2 * SS
    for sgn in (-1, 1):
        exx = hx + sgn * 7 * SS
        d.ellipse([exx - 3 * SS, eye_y - 2 * SS, exx + 3 * SS, eye_y + 2 * SS],
                  fill=shade("eye", 1))
        d.ellipse([exx - 1 * SS, eye_y - 1 * SS, exx + 1 * SS, eye_y + 1 * SS],
                  fill=shade("eye", 0))
    # brow line
    d.line([(hx - 14 * SS, eye_y - 6 * SS), (hx + 14 * SS, eye_y - 6 * SS)],
           fill=shade("line", 1), width=int(1 * SS))

    # -- arms --
    if pose.get("arms", 1):
        draw_arm(d, "L", pose, t, lean)
        draw_arm(d, "R", pose, t, lean)

    # -- cane (idle / intro / win) --
    if pose.get("cane", 0):
        cx0 = 30 * SS
        d.line([(cx0, 60 * SS), (cx0, 158 * SS)], fill=shade("horn", 4), width=int(3 * SS))
        d.ellipse([cx0 - 5 * SS, 52 * SS, cx0 + 5 * SS, 62 * SS], fill=shade("trim", 1))

    # -- energy orb in hand (supers / specials) --
    if pose.get("orb", 0):
        ox, oy = 100 * SS, 66 * SS
        radial_glow(img, ox, oy, 22 * SS, PAL["orb"], strength=1.0)
        d.ellipse([ox - 9 * SS, oy - 9 * SS, ox + 9 * SS, oy + 9 * SS], fill=shade("orb", 2))
        d.ellipse([ox - 4 * SS, oy - 4 * SS, ox + 4 * SS, oy + 4 * SS], fill=shade("orb", 0))


# ----------------------------------------------------------------------------
# VFX drawing
# ----------------------------------------------------------------------------
def vfx_claw_slash():
    w, h = 160, 120
    img, d = new_canvas(w, h)
    cx, cy = 80, 60
    for i in range(4):
        r = 52 - i * 11
        a = int(200 * (1 - i / 4))
        c = PAL["aura"][min(1 + i, len(PAL["aura"]) - 1)]
        d.arc([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS],
              start=200, end=310, fill=(c[0], c[1], c[2], a), width=int((14 - i * 3) * SS))
    d.arc([(cx - 30) * SS, (cy - 30) * SS, (cx + 30) * SS, (cy + 30) * SS],
          start=220, end=290, fill=(255, 255, 255, 160), width=int(5 * SS))
    return finish(img, w, h)


def vfx_orb():
    w, h = 48, 48
    img, d = new_canvas(w, h)
    radial_glow(img, 24 * SS, 24 * SS, 22 * SS, PAL["orb"], 1.0)
    d.ellipse([8 * SS, 8 * SS, 40 * SS, 40 * SS], fill=shade("orb", 3))
    d.ellipse([14 * SS, 14 * SS, 34 * SS, 34 * SS], fill=shade("orb", 0))
    return finish(img, w, h)


def vfx_shockwave():
    w, h = 200, 120
    img, d = new_canvas(w, h)
    cy = 60
    for i in range(4):
        r = 62 - i * 14
        c = PAL["aura"][min(1 + i, len(PAL["aura"]) - 1)]
        a = int(200 * (1 - i / 4))
        d.arc([(100 - r) * SS, (cy - r * 0.4) * SS, (100 + r) * SS, (cy + r * 0.4) * SS],
              start=0, end=180, fill=(c[0], c[1], c[2], a), width=int((12 - i * 2) * SS))
    d.arc([(100 - 20) * SS, (cy - 8) * SS, (100 + 20) * SS, (cy + 8) * SS],
          start=0, end=180, fill=(255, 255, 255, 180), width=int(4 * SS))
    return finish(img, w, h)


def vfx_impact():
    w, h = 96, 96
    img, d = new_canvas(w, h)
    cx = cy = 48
    for i in range(6):
        ang = i * 60 * math.pi / 180
        x1 = cx + 10 * math.cos(ang)
        y1 = cy + 10 * math.sin(ang)
        x2 = cx + 40 * math.cos(ang)
        y2 = cy + 40 * math.sin(ang)
        c = PAL["impact"][min(i + 1, len(PAL["impact"]) - 1)]
        d.line([(x1 * SS, y1 * SS), (x2 * SS, y2 * SS)], fill=(c[0], c[1], c[2], 220),
               width=int(5 * SS))
    d.ellipse([(cx - 14) * SS, (cy - 14) * SS, (cx + 14) * SS, (cy + 14) * SS],
              fill=(255, 255, 255, 230))
    return finish(img, w, h)


def vfx_aura_field():
    w, h = 160, 160
    img, d = new_canvas(w, h)
    radial_glow(img, 80 * SS, 80 * SS, 78 * SS, PAL["aura"], 1.0)
    # inner ring
    d.ellipse([(30) * SS, (30) * SS, (130) * SS, (130) * SS],
              outline=(PAL["aura"][0][0], PAL["aura"][0][1], PAL["aura"][0][2], 160),
              width=int(3 * SS))
    return finish(img, w, h)


def vfx_smoke():
    w, h = 64, 64
    img, d = new_canvas(w, h)
    for i, (ox, oy, r) in enumerate([(32, 34, 26), (22, 40, 18), (42, 40, 18), (32, 46, 14)]):
        c = PAL["smoke"][min(i + 1, len(PAL["smoke"]) - 1)]
        d.ellipse([(ox - r) * SS, (oy - r) * SS, (ox + r) * SS, (oy + r) * SS],
                  fill=(c[0], c[1], c[2], 200))
    return finish(img, w, h)


def vfx_beam():
    w, h = 40, 240
    img, d = new_canvas(w, h)
    for i in range(6):
        r = 18 - i * 3
        c = PAL["aura"][min(1 + i, len(PAL["aura"]) - 1)]
        d.ellipse([(20 - r) * SS, (4) * SS, (20 + r) * SS, (236) * SS],
                  fill=(c[0], c[1], c[2], 180))
    d.ellipse([(14) * SS, (6) * SS, (26) * SS, (234) * SS], fill=(255, 255, 255, 150))
    return finish(img, w, h)


def vfx_spark():
    w, h = 24, 24
    img, d = new_canvas(w, h)
    c = 12
    d.line([(c, 2), (c, 6)], fill=(255, 255, 255, 255), width=int(2 * SS))
    d.line([(c, 18), (c, 22)], fill=(255, 255, 255, 255), width=int(2 * SS))
    d.line([(2, c), (6, c)], fill=(255, 255, 255, 255), width=int(2 * SS))
    d.line([(18, c), (22, c)], fill=(255, 255, 255, 255), width=int(2 * SS))
    d.ellipse([(c - 3) * SS, (c - 3) * SS, (c + 3) * SS, (c + 3) * SS],
              fill=(255, 255, 255, 255))
    return finish(img, w, h)


def vfx_gravity_orb():
    w, h = 48, 48
    img, d = new_canvas(w, h)
    radial_glow(img, 24 * SS, 24 * SS, 22 * SS, PAL["accent"], 1.0)
    d.ellipse([10 * SS, 10 * SS, 38 * SS, 38 * SS], fill=(PAL["accent"][2][0], PAL["accent"][2][1], PAL["accent"][2][2], 220))
    d.ellipse([16 * SS, 16 * SS, 32 * SS, 32 * SS], fill=(PAL["accent"][0][0], PAL["accent"][0][1], PAL["accent"][0][2], 240))
    return finish(img, w, h)


def vfx_eye_flash():
    w, h = 8, 8
    img, d = new_canvas(w, h)
    d.ellipse([0, 0, 8 * SS, 8 * SS], fill=(255, 60, 80, 255))
    return finish(img, w, h)


def vfx_flash():
    """Soft-edged white flash used for screen flashes / super flashes."""
    w, h = 64, 64
    img, d = new_canvas(w, h)
    for i in range(4):
        r = 30 - i * 6
        a = int(255 * (1 - i / 4))
        d.ellipse([(32 - r) * SS, (32 - r) * SS, (32 + r) * SS, (32 + r) * SS],
                  fill=(255, 255, 255, a))
    return finish(img, w, h)


# ----------------------------------------------------------------------------
# Pose catalogue (group 0 = body)
# ----------------------------------------------------------------------------
POSES = {
    # --- idle / locomotion ---
    (0, 0): dict(),                                   # idle A
    (0, 1): dict(breathe=1.4),                        # idle B (breath)
    (0, 2): dict(lean=0.0, armR="hold", cane=0, breathe=2.0),   # walk A
    (0, 3): dict(lean=0.0, armR="down", breathe=-2.0),         # walk B
    (0, 4): dict(crouch=1),                           # crouch
    (0, 5): dict(air=1, arms=1, aura=1.0),            # jump
    (0, 6): dict(air=1, arms=1, aura=0.7, breathe=1.0),  # jump fall
    (0, 8): dict(armR="cross", armL="cross", aura=0.4),   # guard
    # --- hurt / down ---
    (0, 10): dict(lean=0.5, armR="slash", armL="down", aura=0.15),  # hurt stand
    (0, 11): dict(crouch=1, lean=0.5, armR="down", armL="down", aura=0.1),  # hurt crouch
    (0, 12): dict(air=1, lean=0.4, armR="over", armL="over", aura=0.2),  # hurt air
    (0, 13): dict(lie=1),                            # lie down
    # --- standing normals ---
    (0, 20): dict(armR="jab", armL="down"),           # 5LP
    (0, 21): dict(armR="slash", armL="down"),         # 5MP
    (0, 22): dict(armR="over", armL="down", aura=0.9),  # 5HP heavy claw
    (0, 23): dict(armR="jab", armL="hold", lean=0.3, kick=1),  # 5LK
    (0, 24): dict(armR="lance", armL="down", lean=0.4, kick=2, aura=0.8),  # 5HK
    (0, 25): dict(armR="over", armL="over", aura=0.9),  # 6HP overhead
    (0, 26): dict(armR="lance", armL="hold", aura=0.5),  # 4HP lance
    # --- crouching normals ---
    (0, 30): dict(crouch=1, armR="jab", armL="down"),   # 2LP
    (0, 31): dict(crouch=1, armR="slash", armL="down"),  # 2MP
    (0, 32): dict(crouch=1, armR="upper", armL="down", aura=0.8),  # 2HP launcher
    # --- air normals ---
    (0, 40): dict(air=1, armR="jab", armL="down"),      # jLP
    (0, 41): dict(air=1, armR="slash", armL="down"),    # jMP
    (0, 42): dict(air=1, armR="lance", armL="over", lean=0.2, aura=0.8),  # jHK
    # --- intro / win / taunt ---
    (0, 50): dict(cane=1, armR="down", armL="down", aura=1.0),  # intro
    (0, 51): dict(cane=1, armR="down", armL="down", aura=1.2),  # win
    (0, 52): dict(armR="cross", armL="down", aura=0.6),  # taunt
    # --- specials ---
    (0, 60): dict(armR="slash", armL="down", lean=0.3, aura=1.0),   # Hell Claw
    (0, 61): dict(armR="hold", armL="hold", aura=1.1, orb=1),       # Haoshoku
    (0, 62): dict(armR="over", armL="hold", aura=1.0, orb=1),       # Juryoku
    (0, 63): dict(armR="cross", armL="cross", aura=1.3),            # Veil Shift
    (0, 64): dict(armR="jab", armL="jab", lean=-0.2, aura=1.2),     # Aku no Hado
    (0, 65): dict(armR="over", armL="over", aura=1.3),              # Gorosei Judgment
    # --- supers / ultimate ---
    (0, 70): dict(armR="hold", armL="hold", orb=1, aura=1.3),       # Lv1
    (0, 71): dict(armR="over", armL="over", orb=1, aura=1.4),       # Lv2
    (0, 72): dict(armR="cross", armL="cross", orb=1, aura=1.5),     # Lv3
    (0, 73): dict(armR="over", armL="over", orb=1, aura=1.6),       # Ultimate
    # --- awakened / transform ---
    (0, 80): dict(aura=1.8, armR="down", armL="down"),   # awakened idle A
    (0, 81): dict(aura=2.0, armR="down", armL="down", breathe=1.6),  # awakened idle B
    (0, 82): dict(armR="cross", armL="cross", aura=2.2),  # transform
}

VFX = {
    (100, 0): vfx_claw_slash,
    (100, 1): vfx_orb,
    (100, 2): vfx_shockwave,
    (100, 3): vfx_impact,
    (100, 4): vfx_aura_field,
    (100, 5): vfx_smoke,
    (100, 6): vfx_beam,
    (100, 7): vfx_spark,
    (100, 8): vfx_gravity_orb,
    (100, 9): vfx_eye_flash,
    (100, 10): vfx_flash,
}


def draw_body_frame(pose, t=0.0):
    img, d = new_canvas(BODY_W, BODY_H)
    if pose.get("lie", 0):
        # prone figure
        d.ellipse([(18) * SS, (120) * SS, (110) * SS, (160) * SS], fill=shade("robe", 3))
        d.ellipse([(30) * SS, (128) * SS, (98) * SS, (152) * SS], fill=shade("robe", 5))
        d.ellipse([(78) * SS, (108) * SS, (112) * SS, (140) * SS], fill=shade("skin", 1))
        d.ellipse([(86) * SS, (112) * SS, (104) * SS, (126) * SS], fill=shade("beard", 1))
        d.ellipse([(88) * SS, (114) * SS, (94) * SS, (120) * SS], fill=shade("eye", 1))
    else:
        draw_figure(img, pose, t)
    return finish(img, BODY_W, BODY_H)


def generate_all(outdir, preview=False):
    os.makedirs(outdir, exist_ok=True)
    manifest = []
    for (g, n), pose in POSES.items():
        fn = f"{g}_{n}.png"
        img = draw_body_frame(pose, t=(0.0 if n not in (1, 3) else 0.5))
        img.save(os.path.join(outdir, fn), transparency=0)
        manifest.append({"group": g, "image": n, "file": fn,
                         "w": BODY_W, "h": BODY_H, "ax": BODY_AXIS[0], "ay": BODY_AXIS[1],
                         "kind": "body"})
    for (g, n), maker in VFX.items():
        fn = f"{g}_{n}.png"
        img = maker()
        img.save(os.path.join(outdir, fn), transparency=0)
        w, h = img.size
        manifest.append({"group": g, "image": n, "file": fn,
                         "w": w, "h": h, "ax": w // 2, "ay": h // 2,
                         "kind": "vfx"})
    manifest.sort(key=lambda m: (m["group"], m["image"]))
    with open(os.path.join(outdir, "manifest.json"), "w") as f:
        json.dump({"palettes": {"default": MASTER,
                                "gloom": PALETTES["gloom"],
                                "void": PALETTES["void"],
                                "elder": PALETTES["elder"]},
                   "sprites": manifest}, f, indent=2)

    if preview:
        _make_preview(outdir, manifest)
    print(f"[saturn_assets] wrote {len(manifest)} sprites to {outdir}")
    return manifest


def _make_preview(outdir, manifest):
    cols = 10
    rows = (len(manifest) + cols - 1) // cols
    cell = 176
    sheet = Image.new("RGB", (cols * cell, rows * cell), (20, 16, 30))
    d = ImageDraw.Draw(sheet)
    for i, m in enumerate(manifest):
        img = Image.open(os.path.join(outdir, m["file"])).convert("RGBA")
        x = (i % cols) * cell + 8
        y = (i // cols) * cell + 8
        bg = Image.new("RGB", img.size, (30, 24, 44))
        bg.paste(img, (0, 0), img)
        sheet.paste(bg, (x, y))
        d.text((x, y + img.size[1] + 6), f"{m['group']},{m['image']}", fill=(255, 255, 255))
    sheet.save(os.path.join(outdir, "preview.png"))
    print(f"[saturn_assets] preview -> {os.path.join(outdir, 'preview.png')}")


if __name__ == "__main__":
    preview = "--preview" in sys.argv
    base = os.path.join(os.path.dirname(__file__), "..", "chars", "Saturn", "_build")
    generate_all(os.path.join(base, "sprites"), preview=preview)
