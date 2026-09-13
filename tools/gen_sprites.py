#!/usr/bin/env python3
"""
gen_sprites.py - Procedural pixel-art generator for the "Saturn" (One Piece
inspired) IKEMEN GO fan character.

Produces indexed PNG sprites + a JSON manifest + ACT palette files under
chars/Saturn/_build/sprites/. Everything is drawn at native pixel resolution
(hard-edged, no blurry upscaling) against a single shared 256-color master
palette so that SFFv2 palette remapping works.

Usage:
    python3 tools/gen_sprites.py
    python3 tools/gen_sprites.py --preview   # also write preview.png contact sheet

Outputs:
    chars/Saturn/_build/sprites/<group>_<index>.png
    chars/Saturn/_build/sprites/manifest.json
    chars/Saturn/_build/sprites/preview.png          (with --preview)
    chars/Saturn/_build/sprites/Saturn{2,3,4}.act    (selectable palette variants)
"""

import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

# --------------------------------------------------------------------------
# Canvas constants
# --------------------------------------------------------------------------
W, H = 96, 128            # body sprite canvas
AX, AY = 48, 124          # axis (alignment point) at the feet, horizontally centered

OUT_DIR = os.path.join("chars", "Saturn", "_build", "sprites")

# Palette role index ranges (shared layout across every palette)
OUTLINE = 1               # 1px silhouette outline (applied as a post-pass)
DARK = list(range(4, 10))        # 6   near-black shading ramp  (fixed)
ROBE = list(range(10, 20))       # 10  main robe ramp           (remapped)
TRIM = list(range(20, 30))       # 10  gold/trim ramp           (remapped)
SKIN = list(range(30, 40))       # 10  pale skin ramp           (fixed)
HORN = list(range(40, 50))       # 10  bone/horn ramp           (fixed)
EYE = list(range(50, 60))        # 10  eye glow ramp            (fixed)
AURA = list(range(60, 70))       # 10  energy/aura ramp         (remapped)
BEARD = list(range(70, 80))      # 10  white beard/hair ramp    (fixed)
CHITIN = list(range(80, 100))    # 20  dark chitin ramp (spider legs, claws) (fixed)

# --------------------------------------------------------------------------
# Palette helpers
# --------------------------------------------------------------------------
def _hex(c):
    c = c.lstrip("#")
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16))


def ramp(a, b, n):
    """n colors from hex a (dark) to hex b (light), inclusive."""
    fa, fb = _hex(a), _hex(b)
    out = []
    for i in range(n):
        k = i / max(1, n - 1)
        out.append(tuple(int(fa[c] + (fb[c] - fa[c]) * k) for c in range(3)))
    return out


PALETTE_DEFS = {
    # name: (robe_from, robe_to, trim_from, trim_to, aura_from, aura_to)
    "default": ("#1c2e24", "#4f8a63", "#8a6d2f", "#e0bd66", "#5d3a92", "#c58ef0"),
    "gloom":   ("#12303a", "#3d8a96", "#6e7a86", "#d6e2ea", "#3a6b33", "#9adf8a"),
    "void":    ("#160b1e", "#3a1f4f", "#5a1420", "#c43a3a", "#123a5c", "#4a9fe0"),
    "elder":   ("#1d1d22", "#5a5a66", "#8a8378", "#e8e2d4", "#8a3a12", "#f0a14a"),
}


def build_palette(name):
    robe_f, robe_t, trim_f, trim_t, aura_f, aura_t = PALETTE_DEFS[name]
    pal = [(0, 0, 0)] * 256
    pal[0] = (0, 0, 0)                       # index 0 -> transparent (never painted)
    pal[OUTLINE] = (12, 9, 14)               # outline
    pal[4:10] = ramp("#060507", "#241f2a", 6)      # DARK shading
    pal[10:20] = ramp(robe_f, robe_t, 10)          # ROBE
    pal[20:30] = ramp(trim_f, trim_t, 10)          # TRIM
    pal[30:40] = ramp("#3a2e2c", "#c8a89a", 10)    # SKIN
    pal[40:50] = ramp("#3c3834", "#efece2", 10)    # HORN
    pal[50:60] = ramp("#6e1408", "#ffe08a", 10)    # EYE
    pal[60:70] = ramp(aura_f, aura_t, 10)          # AURA
    pal[70:80] = ramp("#7a7a88", "#fbfbff", 10)    # BEARD
    pal[80:100] = ramp("#07070c", "#33333f", 20)   # CHITIN
    return pal


PALETTES = {name: build_palette(name) for name in PALETTE_DEFS}
DEFAULT_PALETTE = PALETTES["default"]


def palette_bytes(pal):
    return sum((list(c) for c in pal), [])


# --------------------------------------------------------------------------
# Low level raster helpers
# --------------------------------------------------------------------------
class Canvas:
    """P-mode drawing surface bound to one palette."""

    def __init__(self, w, h, pal=None):
        self.w, self.h = w, h
        self.pal = pal if pal is not None else DEFAULT_PALETTE
        self.img = Image.new("P", (w, h))
        self.img.putpalette(palette_bytes(self.pal))
        self.d = ImageDraw.Draw(self.img)

    def ellipse(self, cx, cy, rx, ry, fill):
        if rx <= 0 or ry <= 0:
            return
        self.d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill)

    def poly(self, pts, fill):
        self.d.polygon(pts, fill=fill)

    def thick_line(self, p0, p1, width, fill):
        self.d.line([p0[0], p0[1], p1[0], p1[1]], fill=fill, width=width)

    def ring(self, cx, cy, r, width, fill):
        """Outlined circle (pixel-art approximation)."""
        for rr in range(r - width // 2, r + width // 2 + 1):
            if rr <= 0:
                continue
            self.d.arc([cx - rr, cy - rr, cx + rr, cy + rr], 0, 360, fill=fill)

    def radial(self, cx, cy, rmax, ramp_idxs, steps=None):
        """Filled radial gradient using a list of palette indices (outermost last)."""
        steps = steps or len(ramp_idxs)
        for i in range(steps, 0, -1):
            r = rmax * i / steps
            idx = ramp_idxs[min(len(ramp_idxs) - 1, int(len(ramp_idxs) * (steps - i + 1) / steps))]
            self.d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=idx)

    def array(self):
        return np.array(self.img, dtype=np.uint8)


def outline_pass(a):
    """Paint a 1px dark outline on the silhouette edge of an index array."""
    mask = a != 0
    padded = np.pad(mask, 1, constant_values=False)
    edge = mask & (
        ~padded[1:-1, 0:-2] | ~padded[1:-1, 2:] |
        ~padded[0:-2, 1:-1] | ~padded[2:, 1:-1]
    )
    a[edge] = OUTLINE
    return a


# --------------------------------------------------------------------------
# Saturn figure drawing
# --------------------------------------------------------------------------
class Saturn:
    def __init__(self):
        self.canvas = None
        self.d = None

    # ---- shared body parts (canvas absolute coords; feet at y=124) ----

    def _robe(self, c, flare=1.0, y0=58, y1=118):
        """Dark robe bell with a trim belt and hem."""
        top_w = int(13 * flare)
        bot_w = int(23 * flare)
        # body of robe, shaded with horizontal bands
        for i in range(10):
            y_a = y0 + (y1 - y0) * i / 10
            y_b = y0 + (y1 - y0) * (i + 1) / 10
            k = i / 9
            w_a = top_w + (bot_w - top_w) * k
            w_b = top_w + (bot_w - top_w) * (i + 1) / 9
            c.poly([(AX - w_a, y_a), (AX + w_a, y_a), (AX + w_b, y_b), (AX - w_b, y_b)],
                   fill=ROBE[3 + int(6 * k)])
        # belt trim
        c.poly([(AX - top_w - 2, y0 + 26), (AX + top_w + 2, y0 + 26),
                (AX + top_w, y0 + 31), (AX - top_w, y0 + 31)], fill=TRIM[6])
        # hem trim
        c.poly([(AX - bot_w - 2, y1 - 4), (AX + bot_w + 2, y1 - 4),
                (AX + bot_w, y1), (AX - bot_w, y1)], fill=TRIM[5])

    def _feet(self, c, x0=38, x1=58):
        c.ellipse(x0, 122, 5, 3, fill=DARK[4])
        c.ellipse(x1, 122, 5, 3, fill=DARK[4])

    def _head(self, c, cy=40, lean=0.0):
        """Bald head with two curved horns, glowing eyes and a long beard."""
        cx = AX + lean
        # horns (two chained segments each)
        for sgn in (-1, 1):
            hx = cx + sgn * 9
            hy = cy - 9
            c.thick_line((hx, hy), (hx + sgn * 7, hy - 9), 3, fill=HORN[3])
            c.thick_line((hx + sgn * 7, hy - 9), (hx + sgn * 13, hy - 15), 3, fill=HORN[5])
            c.ellipse(hx + sgn * 13, hy - 15, 2, 2, fill=HORN[8])
        # skull
        c.ellipse(cx, cy, 12, 12, fill=SKIN[5])
        c.ellipse(cx, cy - 3, 12, 9, fill=SKIN[7])  # brow highlight
        # eyes
        c.ellipse(cx - 4, cy + 1, 2, 2, fill=EYE[8])
        c.ellipse(cx + 4, cy + 1, 2, 2, fill=EYE[8])
        c.ellipse(cx - 4, cy + 1, 1, 1, fill=EYE[9])
        c.ellipse(cx + 4, cy + 1, 1, 1, fill=EYE[9])
        # beard
        c.poly([(cx - 5, cy + 10), (cx + 5, cy + 10), (cx + 4, cy + 34),
                (cx, cy + 38), (cx - 4, cy + 34)], fill=BEARD[4])
        c.poly([(cx - 2, cy + 10), (cx + 2, cy + 10), (cx + 1, cy + 30),
                (cx, cy + 33), (cx - 1, cy + 30)], fill=BEARD[7])

    def _arm(self, c, shx, shy, exx, exy, hx, hy, sleeve=ROBE[6], hand=SKIN[6]):
        """Two-segment arm: shoulder -> elbow -> hand."""
        c.thick_line((shx, shy), (exx, exy), 5, fill=sleeve)
        c.thick_line((exx, exy), (hx, hy), 4, fill=sleeve)
        c.ellipse(hx, hy, 3, 3, fill=hand)

    def _spider_legs(self, c, n=4, spread=1.0, long=1.0, base=(AX, 112)):
        """Curved chitinous spider legs splayed from the hips."""
        for side in (-1, 1):
            for i in range(n):
                y0 = base[1] - i * 3
                tip_dx = side * (18 + i * 7) * spread
                tip_dy = -4 + i * 6
                mx = AX + side * (6 + i * 3) * spread
                my = y0 - 8 * long
                tx = AX + tip_dx
                ty = y0 + tip_dy
                c.thick_line((AX + side * 4, y0), (mx, my), 3, fill=CHITIN[6])
                c.thick_line((mx, my), (tx, ty), 3, fill=CHITIN[9])
                c.thick_line((tx, ty), (tx + side * 4 * spread, ty + 6), 2, fill=CHITIN[14])

    def _aura(self, c, strength=1.0, cy=70, r=52):
        """Dark energy aura halo behind the figure."""
        if strength <= 0:
            return
        n = int(8 + 8 * strength)
        for i in range(n, 0, -1):
            rr = r * i / n
            k = int(8 * (1 - i / n))
            idx = AURA[k]
            # skip near-transparent outer ring too dense
            c.d.ellipse([AX - rr, cy - rr * 0.8, AX + rr, cy + rr * 0.8], fill=idx)

    # ---- pose composition ----

    def _base(self, aura=0.0, spider=False, cy=40):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        if aura > 0:
            self._aura(c, aura, cy + 30)
        if spider:
            self._spider_legs(c)
        self._robe(c)
        self._feet(c)
        self._head(c, cy=cy)
        return c

    def _finish(self, c, outline=True):
        a = c.array()
        if outline:
            a = outline_pass(a)
        img = Image.fromarray(a, mode="P")
        img.putpalette(palette_bytes(c.pal))
        return img

    # ---- individual poses ----

    def idle(self, variant=0):
        c = self._base(aura=0.25)
        bob = 1 if variant else 0
        self._arm(c, 36, 66 + bob, 32, 80 + bob, 34, 96 + bob)
        self._arm(c, 60, 66 + bob, 64, 80 + bob, 62, 96 + bob)
        return self._finish(c)

    def walk(self, variant=0):
        c = self._base(aura=0.15)
        if variant == 0:
            self._arm(c, 36, 66, 30, 82, 40, 100)
            self._arm(c, 60, 66, 66, 82, 56, 98)
            c.ellipse(42, 122, 6, 3, fill=DARK[5])
            c.ellipse(54, 122, 6, 3, fill=DARK[5])
        else:
            self._arm(c, 36, 66, 42, 80, 34, 98)
            self._arm(c, 60, 66, 54, 82, 62, 100)
            c.ellipse(38, 122, 6, 3, fill=DARK[5])
            c.ellipse(58, 122, 6, 3, fill=DARK[5])
        return self._finish(c)

    def crouch(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.2, cy=95, r=40)
        # crouched robe (shorter, wider)
        for i in range(10):
            y_a = 92 + 22 * i / 10
            y_b = 92 + 22 * (i + 1) / 10
            k = i / 9
            w_a = 22 + 10 * k
            w_b = 22 + 10 * (i + 1) / 9
            c.poly([(AX - w_a, y_a), (AX + w_a, y_a), (AX + w_b, y_b), (AX - w_b, y_b)],
                   fill=ROBE[3 + int(6 * k)])
        c.ellipse(38, 122, 6, 3, fill=DARK[5])
        c.ellipse(58, 122, 6, 3, fill=DARK[5])
        self._head(c, cy=68)
        self._arm(c, 36, 82, 28, 96, 30, 108)
        self._arm(c, 60, 82, 68, 96, 66, 108)
        return self._finish(c)

    def jump(self, fall=False):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.3, cy=40)
        self._robe(c, flare=0.8, y0=62, y1=120)
        if fall:
            self._arm(c, 36, 68, 30, 58, 26, 44)
            self._arm(c, 60, 68, 66, 58, 70, 44)
            c.thick_line((40, 120), (36, 116), 4, fill=DARK[4])
            c.thick_line((56, 120), (60, 116), 4, fill=DARK[4])
        else:
            self._arm(c, 36, 68, 34, 82, 30, 98)
            self._arm(c, 60, 68, 62, 82, 66, 98)
            c.thick_line((40, 120), (34, 116), 4, fill=DARK[4])
            c.thick_line((56, 120), (62, 116), 4, fill=DARK[4])
        self._head(c, cy=38)
        return self._finish(c)

    def guard(self, crouch=False, air=False):
        if air:
            c = self.jump_base()
            c = self._guard_arms(c, cy=52)
            return self._finish(c)
        if crouch:
            c = self.crouch_base()
            c = self._guard_arms(c, cy=80, hx=68)
            return self._finish(c)
        c = self._base(aura=0.2)
        c = self._guard_arms(c, cy=50)
        return self._finish(c)

    def _guard_arms(self, c, cy=50, hx=66):
        self._arm(c, 36, cy + 16, 34, cy + 22, 40, cy + 30)
        self._arm(c, 60, cy + 16, 62, cy + 22, hx, cy + 30)
        # a faint energy shield
        c.ring(AX, cy + 6, 26, 2, fill=AURA[6])
        c.ring(AX, cy + 6, 22, 2, fill=AURA[8])
        return c

    def jump_base(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.3, cy=40)
        self._robe(c, flare=0.8, y0=62, y1=120)
        c.thick_line((40, 120), (34, 116), 4, fill=DARK[4])
        c.thick_line((56, 120), (62, 116), 4, fill=DARK[4])
        self._head(c, cy=38)
        return c

    def crouch_base(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.2, cy=95, r=40)
        for i in range(10):
            y_a = 92 + 22 * i / 10
            y_b = 92 + 22 * (i + 1) / 10
            k = i / 9
            w_a = 22 + 10 * k
            w_b = 22 + 10 * (i + 1) / 9
            c.poly([(AX - w_a, y_a), (AX + w_a, y_a), (AX + w_b, y_b), (AX - w_b, y_b)],
                   fill=ROBE[3 + int(6 * k)])
        c.ellipse(38, 122, 6, 3, fill=DARK[5])
        c.ellipse(58, 122, 6, 3, fill=DARK[5])
        self._head(c, cy=68)
        return c

    def hit(self, kind="high"):
        """Hit-reaction recoil poses."""
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        if kind == "high":
            self._aura(c, 0.2)
            self._robe(c)
            self._feet(c)
            self._head(c, cy=40, lean=4)
            self._arm(c, 36, 66, 30, 78, 24, 92)
            self._arm(c, 60, 66, 70, 74, 76, 66)
        elif kind == "crouch":
            self._aura(c, 0.15, cy=95, r=40)
            for i in range(10):
                y_a = 92 + 22 * i / 10
                y_b = 92 + 22 * (i + 1) / 10
                k = i / 9
                w_a = 22 + 10 * k
                w_b = 22 + 10 * (i + 1) / 9
                c.poly([(AX - w_a, y_a), (AX + w_a, y_a), (AX + w_b, y_b), (AX - w_b, y_b)],
                       fill=ROBE[3 + int(6 * k)])
            c.ellipse(38, 122, 6, 3, fill=DARK[5])
            c.ellipse(58, 122, 6, 3, fill=DARK[5])
            self._head(c, cy=68, lean=3)
            self._arm(c, 36, 82, 26, 96, 20, 106)
            self._arm(c, 60, 82, 70, 92, 76, 84)
        elif kind == "air":
            self._aura(c, 0.2)
            self._robe(c, flare=0.8, y0=62, y1=120)
            c.thick_line((40, 120), (34, 116), 4, fill=DARK[4])
            c.thick_line((56, 120), (62, 116), 4, fill=DARK[4])
            self._head(c, cy=36, lean=5)
            self._arm(c, 36, 66, 26, 60, 20, 46)
            self._arm(c, 60, 66, 70, 60, 76, 46)
        return self._finish(c)

    def liedown(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        # prone figure
        c.ellipse(AX, 116, 30, 8, fill=ROBE[6])
        c.ellipse(AX - 26, 118, 7, 5, fill=ROBE[8])
        c.ellipse(AX - 34, 112, 7, 5, fill=SKIN[6])
        self._arm(c, AX - 30, 114, AX - 14, 110, AX - 4, 108)
        self._arm(c, AX + 8, 112, AX + 22, 110, AX + 30, 112)
        return self._finish(c)

    def getup(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        # rising crouch
        for i in range(10):
            y_a = 96 + 20 * i / 10
            y_b = 96 + 20 * (i + 1) / 10
            k = i / 9
            w_a = 20 + 8 * k
            w_b = 20 + 8 * (i + 1) / 9
            c.poly([(AX - w_a, y_a), (AX + w_a, y_a), (AX + w_b, y_b), (AX - w_b, y_b)],
                   fill=ROBE[3 + int(6 * k)])
        c.ellipse(38, 122, 6, 3, fill=DARK[5])
        c.ellipse(58, 122, 6, 3, fill=DARK[5])
        self._head(c, cy=72)
        self._arm(c, 36, 86, 34, 100, 32, 112)
        self._arm(c, 60, 86, 62, 100, 64, 112)
        return self._finish(c)

    def win(self):
        c = self._base(aura=0.8, cy=40)
        self._arm(c, 36, 66, 24, 54, 18, 40)
        self._arm(c, 60, 66, 72, 54, 78, 40)
        c.radial(AX, 30, 20, AURA[:9])
        return self._finish(c)

    def intro(self):
        c = self._base(aura=0.6, cy=40)
        self._arm(c, 36, 66, 40, 84, 42, 102)
        self._arm(c, 60, 66, 56, 84, 54, 102)
        return self._finish(c)

    def taunt(self):
        c = self._base(aura=0.4, cy=40)
        self._arm(c, 36, 66, 28, 78, 24, 92)
        self._arm(c, 60, 66, 68, 78, 72, 92)
        return self._finish(c)

    def lose(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.1, cy=70)
        self._robe(c)
        self._feet(c)
        self._head(c, cy=44, lean=-2)
        self._arm(c, 36, 66, 34, 84, 36, 100)
        self._arm(c, 60, 66, 62, 84, 60, 100)
        return self._finish(c)

    # ---- attack poses (normals) ----

    def punch(self):
        c = self._base(aura=0.2)
        self._arm(c, 36, 66, 30, 76, 26, 84)
        self._arm(c, 60, 66, 72, 60, 82, 56)  # punching arm forward
        return self._finish(c)

    def claw(self):
        c = self._base(aura=0.25)
        self._arm(c, 36, 66, 30, 80, 28, 96)
        self._arm(c, 60, 66, 74, 56, 86, 50)  # extended claw swipe
        for dx in (78, 82, 86):
            c.thick_line((AX + 10, 60), (dx, 46), 2, fill=CHITIN[12])
        return self._finish(c)

    def slam(self):
        c = self._base(aura=0.3)
        self._arm(c, 36, 66, 26, 54, 22, 40)   # arm raised
        self._arm(c, 60, 66, 70, 54, 74, 40)   # arm raised
        return self._finish(c)

    def kick(self, height=0.5):
        c = self._base(aura=0.2)
        self._arm(c, 36, 66, 32, 80, 34, 96)
        self._arm(c, 60, 66, 66, 80, 62, 94)
        y_tip = 96 - height * 40
        c.thick_line((54, 118), (72, y_tip), 5, fill=DARK[5])
        c.ellipse(72, y_tip, 4, 4, fill=DARK[4])
        return self._finish(c)

    def lunge(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.35, cy=70)
        self._spider_legs(c, n=2, spread=0.6)
        self._robe(c, y0=64, y1=118)
        self._feet(c, 44, 64)
        self._head(c, cy=40, lean=6)
        self._arm(c, 36, 68, 26, 80, 20, 92)
        self._arm(c, 60, 68, 78, 62, 88, 56)  # lunging claw
        return self._finish(c)

    def sweep(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.15, cy=95, r=40)
        for i in range(10):
            y_a = 92 + 22 * i / 10
            y_b = 92 + 22 * (i + 1) / 10
            k = i / 9
            w_a = 22 + 10 * k
            w_b = 22 + 10 * (i + 1) / 9
            c.poly([(AX - w_a, y_a), (AX + w_a, y_a), (AX + w_b, y_b), (AX - w_b, y_b)],
                   fill=ROBE[3 + int(6 * k)])
        c.ellipse(38, 122, 6, 3, fill=DARK[5])
        c.ellipse(58, 122, 6, 3, fill=DARK[5])
        self._head(c, cy=68, lean=3)
        c.thick_line((52, 118), (86, 114), 5, fill=DARK[5])  # sweeping leg
        c.ellipse(86, 114, 4, 4, fill=DARK[4])
        self._arm(c, 36, 82, 30, 96, 32, 108)
        self._arm(c, 60, 82, 66, 94, 62, 106)
        return self._finish(c)

    def crouch_punch(self):
        c = self.crouch_base()
        self._arm(c, 36, 82, 28, 96, 26, 108)
        self._arm(c, 60, 82, 74, 78, 82, 74)  # low jab
        return self._finish(c)

    def crouch_kick(self):
        c = self.crouch_base()
        self._arm(c, 36, 82, 30, 96, 32, 108)
        c.thick_line((50, 116), (70, 110), 5, fill=DARK[5])
        c.ellipse(70, 110, 4, 4, fill=DARK[4])
        return self._finish(c)

    def launcher(self):
        c = self.crouch_base()
        self._arm(c, 36, 82, 26, 74, 22, 62)   # rising claw
        self._arm(c, 60, 82, 70, 74, 74, 62)
        return self._finish(c)

    def air_punch(self):
        c = self.jump_base()
        self._arm(c, 36, 66, 28, 62, 24, 52)
        self._arm(c, 60, 66, 72, 56, 82, 50)
        return self._finish(c)

    def air_kick(self):
        c = self.jump_base()
        self._arm(c, 36, 66, 30, 78, 32, 92)
        c.thick_line((52, 118), (72, 96), 5, fill=DARK[5])
        c.ellipse(72, 96, 4, 4, fill=DARK[4])
        return self._finish(c)

    def air_slam(self):
        c = self.jump_base()
        self._arm(c, 36, 66, 30, 60, 26, 48)
        self._arm(c, 60, 66, 72, 60, 78, 48)
        return self._finish(c)

    # ---- special / super / ultimate poses ----

    def venom_throw(self):
        c = self._base(aura=0.3)
        self._arm(c, 36, 66, 30, 78, 28, 92)
        self._arm(c, 60, 66, 74, 58, 82, 52)  # arm thrust, orb
        return self._finish(c)

    def skitter(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.4, cy=80)
        self._spider_legs(c, n=4, spread=1.2, long=1.2)
        self._robe(c, y0=68, y1=118)
        self._head(c, cy=46, lean=6)
        self._arm(c, 36, 72, 30, 84, 26, 96)
        self._arm(c, 60, 72, 72, 66, 80, 58)
        return self._finish(c)

    def horn_rise(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.5, cy=60)
        self._robe(c, flare=0.7, y0=66, y1=120)
        self._head(c, cy=40)
        # charge horns lowered
        c.thick_line((40, 34), (30, 24), 4, fill=HORN[6])
        c.thick_line((56, 34), (66, 24), 4, fill=HORN[6])
        self._arm(c, 36, 68, 30, 80, 28, 96)
        self._arm(c, 60, 68, 66, 80, 68, 96)
        return self._finish(c)

    def stare(self):
        c = self._base(aura=0.5)
        self._arm(c, 36, 66, 26, 72, 20, 80)
        self._arm(c, 60, 66, 70, 72, 76, 80)
        # bright eyes
        c.ellipse(AX - 4, 41, 3, 3, fill=EYE[9])
        c.ellipse(AX + 4, 41, 3, 3, fill=EYE[9])
        return self._finish(c)

    def leg_drill(self):
        c = Canvas(W, H)
        self.canvas = c
        self.d = c.d
        self._aura(c, 0.4, cy=70)
        self._spider_legs(c, n=4, spread=1.4, long=1.3)
        self._robe(c, y0=66, y1=118)
        self._head(c, cy=44)
        self._arm(c, 36, 70, 30, 82, 28, 96)
        self._arm(c, 60, 70, 66, 82, 68, 96)
        return self._finish(c)

    def heal(self):
        c = self._base(aura=0.9, cy=40)
        self._arm(c, 36, 66, 42, 84, 44, 100)
        self._arm(c, 60, 66, 54, 84, 52, 100)
        c.radial(AX, 40, 16, AURA[:8])
        return self._finish(c)

    def judgement(self):
        c = self._base(aura=0.8, cy=40)
        self._arm(c, 36, 66, 28, 56, 22, 42)
        self._arm(c, 60, 66, 70, 56, 76, 42)
        return self._finish(c)

    def wrath(self):
        c = self._base(aura=0.7, cy=40, spider=True)
        self._arm(c, 36, 66, 30, 60, 26, 46)
        self._arm(c, 60, 66, 66, 60, 70, 46)
        return self._finish(c)

    def death_stare(self):
        c = self._base(aura=0.9, cy=40)
        self._arm(c, 36, 66, 24, 66, 18, 70)
        self._arm(c, 60, 66, 72, 66, 78, 70)
        c.ellipse(AX - 5, 41, 4, 4, fill=EYE[9])
        c.ellipse(AX + 5, 41, 4, 4, fill=EYE[9])
        c.ellipse(AX, 41, 3, 3, fill=EYE[8])
        return self._finish(c)

    def ultimate(self):
        c = self._base(aura=1.0, cy=40, spider=True)
        self._arm(c, 36, 66, 24, 48, 18, 32)
        self._arm(c, 60, 66, 72, 48, 78, 32)
        c.radial(AX, 30, 22, AURA[:9])
        return self._finish(c)

    def awakening(self):
        c = self._base(aura=1.0, cy=40, spider=True)
        self._arm(c, 36, 66, 30, 60, 28, 48)
        self._arm(c, 60, 66, 66, 60, 68, 48)
        c.radial(AX, 30, 24, AURA[:9])
        return self._finish(c)

    def awakened_idle(self):
        c = self._base(aura=0.5, cy=40, spider=True)
        self._arm(c, 36, 66, 32, 82, 34, 100)
        self._arm(c, 60, 66, 64, 82, 62, 100)
        return self._finish(c)

    def awakened_claw(self):
        c = self._base(aura=0.5, cy=40, spider=True)
        self._arm(c, 36, 66, 28, 76, 24, 90)
        self._arm(c, 60, 66, 76, 54, 88, 48)
        return self._finish(c)


# --------------------------------------------------------------------------
# VFX sprites (drawn as glows / shapes; no outline pass)
# --------------------------------------------------------------------------
def vfx_orb(size=64):
    c = Canvas(size, size)
    c.radial(size // 2, size // 2, size // 2 - 2, AURA[:10])
    c.ellipse(size // 2, size // 2, 6, 6, fill=EYE[9])
    return c.img


def vfx_barb(size=64):
    c = Canvas(size, size)
    cx, cy = size // 2, size // 2
    c.radial(cx, cy, size // 2 - 2, list(reversed(AURA[:10])))
    # barb spikes
    for ang in (0, 60, 120, 180, 240, 300):
        tx = cx + int(math.cos(math.radians(ang)) * (size // 2 - 4))
        ty = cy + int(math.sin(math.radians(ang)) * (size // 2 - 4))
        c.thick_line((cx, cy), (tx, ty), 3, fill=CHITIN[14])
    c.ellipse(cx, cy, 5, 5, fill=EYE[8])
    return c.img


def vfx_slash(size=96, color=CHITIN[14], spark=EYE[8]):
    c = Canvas(size, size)
    c.d.arc([6, 6, size - 6, size - 6], 200, 320, fill=color, width=5)
    c.d.arc([10, 10, size - 10, size - 10], 200, 320, fill=spark, width=2)
    return c.img


def vfx_ring(size_w=128, size_h=64):
    c = Canvas(size_w, size_h)
    cx, cy = size_w // 2, size_h // 2
    for rr in range(10, 0, -1):
        k = int(9 * (1 - rr / 10))
        c.d.ellipse([cx - 40 * rr / 10, cy - 12 * rr / 10,
                     cx + 40 * rr / 10, cy + 12 * rr / 10], outline=AURA[k], width=2)
    return c.img


def vfx_burst(size=96):
    c = Canvas(size, size)
    cx, cy = size // 2, size // 2
    c.radial(cx, cy, size // 2 - 2, AURA[:10])
    for ang in range(0, 360, 30):
        tx = cx + int(math.cos(math.radians(ang)) * (size // 2 - 2))
        ty = cy + int(math.sin(math.radians(ang)) * (size // 2 - 2))
        c.thick_line((cx, cy), (tx, ty), 3, fill=EYE[8])
    c.ellipse(cx, cy, 10, 10, fill=EYE[9])
    return c.img


def vfx_pillar(size_w=96, size_h=192):
    c = Canvas(size_w, size_h)
    cx = size_w // 2
    for i in range(16):
        y_a = size_h - 6 - (size_h - 12) * i / 16
        y_b = size_h - 6 - (size_h - 12) * (i + 1) / 16
        k = i / 15
        w = 6 + 30 * k
        c.poly([(cx - w, y_a), (cx + w, y_a), (cx + w - 4, y_b), (cx - w + 4, y_b)],
               fill=AURA[2 + int(7 * k)])
    c.ellipse(cx, size_h - 6, 34, 6, fill=AURA[4])
    return c.img


def vfx_bind(size=128):
    c = Canvas(size, size)
    cx = size // 2
    c.ring(cx, cx, size // 2 - 6, 4, fill=AURA[7])
    c.ring(cx, cx, size // 2 - 14, 3, fill=AURA[9])
    # runes
    for i in range(8):
        ang = math.radians(i * 45)
        rx = cx + int(math.cos(ang) * (size // 2 - 10))
        ry = cx + int(math.sin(ang) * (size // 2 - 10))
        c.ellipse(rx, ry, 4, 4, fill=EYE[8])
    return c.img


def vfx_flame(size_w=96, size_h=128):
    c = Canvas(size_w, size_h)
    cx, base = size_w // 2, size_h - 4
    for i in range(12):
        y_a = base - (size_h - 8) * i / 12
        y_b = base - (size_h - 8) * (i + 1) / 12
        k = i / 11
        w = 20 + 16 * k
        c.poly([(cx - w, y_a), (cx + w, y_a), (cx + w - 3, y_b), (cx - w + 3, y_b)],
               fill=AURA[1 + int(8 * k)])
    return c.img


def vfx_spark(size=32):
    c = Canvas(size, size)
    cx = size // 2
    c.radial(cx, cx, size // 2 - 2, [EYE[9], EYE[8], EYE[6], EYE[4]])
    return c.img


def vfx_flash(size=8):
    c = Canvas(size, size)
    c.ellipse(size // 2, size // 2, size // 2 - 1, size // 2 - 1, fill=EYE[9])
    return c.img


# --------------------------------------------------------------------------
# Sprite registry
# --------------------------------------------------------------------------
def body_list():
    s = Saturn()
    return [
        (0, s.idle(0)), (1, s.idle(1)),
        (2, s.walk(0)), (3, s.walk(1)),
        (4, s.crouch()),
        (5, s.jump(False)), (6, s.jump(True)),
        (7, s.guard(False)), (8, s.guard(True)), (9, s.guard(air=True)),
        (10, s.hit("high")), (11, s.hit("crouch")), (12, s.hit("crouch")),
        (13, s.hit("air")),
        (14, s.liedown()), (15, s.getup()),
        (16, s.win()), (17, s.intro()), (18, s.taunt()), (19, s.lose()),
        (20, s.punch()), (21, s.claw()), (22, s.slam()),
        (23, s.kick(0.3)), (24, s.kick(0.6)), (25, s.kick(0.9)),
        (26, s.lunge()), (27, s.sweep()),
        (28, s.crouch_punch()), (29, s.crouch_kick()), (30, s.launcher()),
        (31, s.air_punch()), (32, s.air_kick()), (33, s.air_slam()),
        (34, s.venom_throw()), (35, s.skitter()), (36, s.horn_rise()),
        (37, s.stare()), (38, s.leg_drill()), (39, s.heal()),
        (40, s.judgement()), (41, s.wrath()), (42, s.death_stare()),
        (43, s.ultimate()), (44, s.awakening()),
        (45, s.awakened_idle()), (46, s.awakened_claw()),
    ]


def vfx_list():
    return [
        (0, vfx_orb()), (1, vfx_barb()), (2, vfx_slash()),
        (3, vfx_slash(color=CHITIN[9], spark=EYE[6])),
        (4, vfx_ring()), (5, vfx_burst()), (6, vfx_pillar()),
        (7, vfx_bind()), (8, vfx_flame()), (9, vfx_spark()), (10, vfx_flash()),
    ]


# --------------------------------------------------------------------------
# ACT palette files
# --------------------------------------------------------------------------
def write_act(path, pal):
    with open(path, "wb") as f:
        for c in pal:
            f.write(bytes(c))  # 3 bytes RGB, no alpha


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
def main():
    preview = "--preview" in sys.argv
    os.makedirs(OUT_DIR, exist_ok=True)

    manifest = {"sprites": [], "palettes": {}}

    # bodies
    for idx, img in body_list():
        fn = f"0_{idx}.png"
        img.save(os.path.join(OUT_DIR, fn))
        manifest["sprites"].append({
            "group": 0, "index": idx, "file": fn,
            "w": W, "h": H, "axis": [AX, AY],
        })

    # vfx
    for idx, img in vfx_list():
        fn = f"100_{idx}.png"
        img.save(os.path.join(OUT_DIR, fn))
        w, h = img.size
        manifest["sprites"].append({
            "group": 100, "index": idx, "file": fn,
            "w": w, "h": h, "axis": [w // 2, h - 4],
        })

    # palettes (default + variants), recorded as RGBA lists
    for name, pal in PALETTES.items():
        manifest["palettes"][name] = [[c[0], c[1], c[2], 255 if i else 0]
                                      for i, c in enumerate(pal)]

    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)

    # ACT variant palettes (1 = default, 2..4 = variants)
    for i, name in enumerate(["default", "gloom", "void", "elder"]):
        write_act(os.path.join(OUT_DIR, f"Saturn{i+1}.act"), PALETTES[name])

    if preview:
        cols = 8
        items = manifest["sprites"]
        rows = (len(items) + cols - 1) // cols
        cell = max(W, 96) + 8
        sheet = Image.new("P", (cols * cell, rows * (H + 8)))
        sheet.putpalette(palette_bytes(DEFAULT_PALETTE))
        for i, sp in enumerate(items):
            im = Image.open(os.path.join(OUT_DIR, sp["file"]))
            r, c = divmod(i, cols)
            sheet.paste(im, (c * cell + 4, r * (H + 8) + 4))
        sheet.save(os.path.join(OUT_DIR, "preview.png"))

    print(f"Wrote {len(manifest['sprites'])} sprites + {len(PALETTES)} palettes to {OUT_DIR}")


if __name__ == "__main__":
    main()
