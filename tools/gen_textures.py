#!/usr/bin/env python3
"""Generate every PNG the mod's models and renderers reference.

Why procedural
--------------
The mod is distributed with its source and there is no binary asset pipeline here, so the textures
have to be reproducible from the repository. Everything below is deterministic: a 16x16 pixel grid
computed from a hash of its coordinates, so re-running the script gives byte-identical output and a
diff of the textures folder means somebody changed the palette, not that a generator reran.

These are placeholders in the sense of "art, not code": the shapes and palettes are chosen so the
blocks read correctly at a glance (machine / glass / scorched earth / charred wood) and so the entity
overlays work as intended (a radial glow, a soft puff). Drop real art in
``src/main/resources/assets/doomsday/textures/`` with the same names whenever an artist shows up —
nothing in Java references a size or a layout beyond the 16x16 model UVs.

Usage:  python3 tools/gen_textures.py
"""

from __future__ import annotations

import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "doomsday", "textures")

# ———————————————————————————————————————————————————————————— PNG writer
def write_png(path: str, size: int, pixels) -> None:
    """``pixels(y, x) -> (r, g, b, a)``; written as a truecolour+alpha PNG, no filtering."""
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # filter type 0 (None) per scanline
        for x in range(size):
            # Clamp centrally: the procedural patterns above add and subtract noise around a base
            # colour, and a palette value that drifts past 255 or below 0 is a generator bug that
            # should not turn into an exception — one clamp here keeps every texture function free of
            # bounds bookkeeping.
            raw.extend(max(0, min(255, int(c))) for c in pixels(y, x))
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)


def h(x: int, y: int, salt: int = 0) -> int:
    """A stable 0..255 hash of a pixel. Good enough for grain; not for gameplay noise."""
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n ^= n >> 13
    n = (n * 1274126177) & 0xFFFFFFFF
    return (n ^ (n >> 16)) & 0xFF


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def shade(rgb, k, a=255):
    return tuple(max(0, min(255, int(c * k))) for c in rgb) + (a,)


# ————————————————————————————————————————————————————————— block textures
def metal(side: str):
    def px(y, x):
        base = (58, 60, 66) if side == "steel" else (92, 84, 74)
        n = h(x, y, 1) % 24
        rgb = (base[0] + n - 12, base[1] + n - 12, base[2] + n - 12)
        # panel seams: two horizontal grooves and a vertical one
        if y in (4, 11) or x == 8 and y < 11:
            rgb = shade(rgb, 0.62)
        # hazard chevrons across the bottom two rows
        if y >= 13 and ((x + y) % 4) < 2:
            rgb = (206, 168, 34)
        elif y >= 13:
            rgb = (26, 24, 22)
        # corner bolts
        if (x, y) in [(2, 2), (13, 2), (2, 12), (13, 12)]:
            rgb = (150, 152, 158)
        return rgb + (255,)
    return px


def console_tex(preset: str):
    """The control face: a dark slab with a lit band whose colour is the preset's identity."""
    accent = {"standard": (96, 226, 120), "little_boy": (232, 196, 92),
              "fat_man": (236, 128, 76), "tsar_bomba": (226, 74, 74)}[preset]

    def px(y, x):
        rgb = (28, 30, 34)
        if y in (0, 15) or x in (0, 15):
            rgb = (16, 17, 19)
        if 3 <= y <= 6:  # screen
            on = h(x, y, 7) % 5 != 0
            rgb = mix((12, 18, 14), accent, 0.85) if on else (20, 26, 22)
        if y >= 9 and (x % 3) in (0, 1):  # keypad
            rgb = (78, 80, 86)
        if (x, y) == (13, 1):
            rgb = (255, 96, 84) if h(x, y, 3) % 2 else (120, 40, 36)
        return rgb + (255,)
    return px


def top_tex(preset: str):
    """Lid: concentric hatch with a lift-eye in the middle."""
    def px(y, x):
        d = max(abs(x - 7.5), abs(y - 7.5))
        rgb = (72, 74, 80)
        if int(d * 2) % 2 == 0:
            rgb = (92, 94, 100)
        if d > 6.6:
            rgb = (40, 41, 45)
        if d < 2.2:
            rgb = (188, 158, 52) if preset == "standard" else (170, 172, 178)
        if d < 1.1:
            rgb = (22, 22, 24)
        n = h(x, y, 11) % 16 - 8
        return (rgb[0] + n, rgb[1] + n, rgb[2] + n) + (255,)
    return px


def vitrified(y, x):
    """Fused glass-sand: pale greenish base, long streaks, bright specular flecks."""
    n = h(x, y, 21) % 18
    rgb = mix((168, 196, 172), (214, 236, 214), ((x + y // 2) % 4) / 4.0)
    if (x * 3 + y) % 11 < 2:
        rgb = shade(rgb, 0.72)
    if h(x, y, 5) % 23 == 0:
        rgb = (252, 255, 246)
    return (rgb[0] + n - 9, rgb[1] + n - 9, rgb[2] + n - 9) + (255,)


def heated(y, x):
    """Grey stone with glowing cracks — the crack pattern is a stripe field, not noise, so it
    still looks like geology at 16x16."""
    n = h(x, y, 31) % 22 - 11
    rgb = (112 + n, 108 + n, 106 + n)
    crack = abs(((x * 2 + y * 3) % 13) - 6) + abs(((x * 5 - y) % 9) - 4)
    if crack < 2:
        t = h(x, y, 13) % 3
        rgb = [(236, 120, 34), (252, 178, 62), (196, 62, 22)][t]
    elif crack < 3:
        rgb = mix(rgb, (224, 108, 30), 0.45)
    return rgb + (255,)


def heated_top(y, x):
    """Top face reads hotter: more glow, less stone."""
    n = h(x, y, 41) % 20 - 10
    rgb = (126 + n, 112 + n, 100 + n)
    if h(x, y, 17) % 7 == 0:
        rgb = (240, 150, 48)
    if h(x, y, 3) % 13 == 0:
        rgb = (86, 60, 44)
    return rgb + (255,)


def scorched(y, x):
    """Dry, cracked, dark brown. The cracks are darker and slightly redder, not black."""
    n = h(x, y, 51) % 26 - 13
    rgb = (74 + n, 52 + n, 36 + n)
    if (x + y * 2) % 9 < 1 or (x * 2 + y) % 11 < 1:
        rgb = (40, 26, 18)
    if h(x, y, 7) % 19 == 0:
        rgb = (122, 74, 40)
    return rgb + (255,)


def bark(y, x):
    """Charred log side: vertical fissures in near-black carbon with a few live embers."""
    col = h(x, 0, 61) % 4
    rgb = (30 + col * 6, 26 + col * 5, 24 + col * 4)
    rgb = (rgb[0] + h(x, y, 3) % 10, rgb[1] + h(x, y, 4) % 8, rgb[2] + h(x, y, 5) % 6)
    if y in (0, 15):
        rgb = (18, 16, 15)
    if (x + (y // 4)) % 6 < 2:
        rgb = (16, 14, 13)
    if h(x, y, 29) % 29 == 0:
        rgb = (196, 92, 28)
    return rgb + (255,)


def rings(y, x):
    """Charred log top: growth rings, burnt at the edge."""
    d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
    rgb = (60, 46, 36) if int(d) % 2 else (84, 66, 50)
    if d > 6.9:
        rgb = (22, 18, 16)
    if d < 1.3:
        rgb = (110, 86, 62)
    n = h(x, y, 71) % 12 - 6
    return (rgb[0] + n, rgb[1] + n, rgb[2] + n) + (255,)


# ———————————————————————————————————————————————————————— item textures
def goggles(y, x):
    """Hazmat goggles: a lens band with a rubber strap and a bright reflection."""
    if y < 4 or y > 12:
        return (0, 0, 0, 0)
    if x in (0, 1, 14, 15):
        return (44, 44, 40, 255)  # strap
    if 5 <= y <= 11 and 2 <= x <= 13:
        lens = mix((112, 196, 214), (36, 78, 96), abs(x - 7) / 8.0)
        if (x - 2 * y) % 7 == 0:
            lens = (236, 250, 255)
        return lens + (255,)
    return (28, 30, 32, 255)


def counter(y, x):
    """Geiger counter: a box with a speaker grille, a needle dial and a headphone jack."""
    if not (2 <= y <= 13 and 1 <= x <= 14):
        return (0, 0, 0, 0)
    rgb = (128, 108, 58)  # bakelite
    if h(x, y, 81) % 17 == 0:
        rgb = (112, 94, 50)
    if 4 <= y <= 6 and 3 <= x <= 12:
        rgb = (226, 226, 206)  # dial
        if abs(x - 8) + abs(y - 5) < 4 and (x - 8) > (y - 5) * 2:
            rgb = (196, 44, 36)  # needle
    if 8 <= y <= 12 and 3 <= x <= 12:
        rgb = (40, 40, 40) if (x + y) % 2 else (72, 72, 72)  # grille
    if y in (2, 13) or x in (1, 14):
        rgb = shade(rgb, 0.7)
    return rgb + (255,)


def detonator(y, x):
    """Remote detonator: an antenna, a big red button, a small screen."""
    if not (1 <= y <= 14 and 2 <= x <= 13):
        return (0, 0, 0, 0)
    rgb = (58, 60, 64)
    if y <= 3 and x == 11:
        rgb = (150, 152, 156)  # antenna
    if 4 <= y <= 6 and 4 <= x <= 11:
        rgb = (22, 46, 30) if h(x, y, 91) % 4 else (52, 168, 96)  # screen
    if 8 <= y <= 12 and 4 <= x <= 8:
        d = max(abs(x - 6), abs(y - 10))
        rgb = (196, 44, 38) if d <= 2 else (96, 26, 24)  # button
    if 8 <= y <= 12 and 10 <= x <= 12:
        rgb = (30, 32, 34)  # trigger guard
    if x in (2, 13) or y in (14,):
        rgb = shade(rgb, 0.66)
    return rgb + (255,)


def tablet(y, x):
    """Iodine tablet: a small amber pill in a blister pack."""
    if not (3 <= y <= 12 and 3 <= x <= 12):
        return (0, 0, 0, 0)
    d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
    if d > 5.0:
        return (196, 198, 200, 255) if (x + y) % 4 else (150, 152, 156)  # foil
    rgb = mix((236, 168, 58), (150, 84, 20), d / 5.0)
    if d < 1.6:
        rgb = (255, 240, 200)
    if abs(d - 4.6) < 0.5:
        rgb = shade(rgb, 0.72)
    return rgb + (255,)


# ———————————————————————————————————————————————————— entity overlays
def radial(size: int, inner=(255, 255, 255), hardness=0.42):
    def px(y, x):
        d = ((x + 0.5 - size / 2) ** 2 + (y + 0.5 - size / 2) ** 2) ** 0.5 / (size / 2)
        if d >= 1.0:
            return (0, 0, 0, 0)
        a = max(0.0, min(1.0, 1.0 - (d / hardness) ** 2)) ** 1.35
        return (int(inner[0] * a + 255 * (1 - a) * 0.0), inner[1], inner[2], int(255 * a))
    return px


def puff(size: int):
    """A soft cloud blob: value noise over a radial falloff, so it never looks like a circle."""
    def smooth(gx, gy):
        acc = 0
        for oy in (-1, 0, 1):
            for ox in (-1, 0, 1):
                acc += h(gx + ox, gy + oy, 101)
        return acc / 9.0

    def px(y, x):
        d = ((x + 0.5 - size / 2) ** 2 + (y + 0.5 - size / 2) ** 2) ** 0.5 / (size / 2)
        if d >= 1.0:
            return (0, 0, 0, 0)
        n = (smooth(x // 2, y // 2) / 255.0) * 0.55 + (smooth(x, y) / 255.0) * 0.45
        fall = max(0.0, 1.0 - d ** 1.7)
        a = max(0.0, min(1.0, (0.35 + n) * fall * 1.5))
        g = int(150 + 90 * n)
        return (g, g, g, int(255 * a))
    return px


def icon(y, x, size=128):
    """128x128: a mushroom cloud over a lit horizon. Silhouette first, so it reads at 16 px too."""
    fy = y / size
    top = mix((28, 22, 40), (232, 116, 44), max(0.0, (fy - 0.42) / 0.58))
    if fy > 0.78:
        top = mix(top, (18, 16, 20), 0.7)
    cx, cy = size * 0.5, size * 0.30
    cap = (((x - cx) / (size * 0.34)) ** 2 + ((y - cy) / (size * 0.17)) ** 2) ** 0.5
    stem = abs(x - cx) / (size * 0.11) + max(0.0, (size * 0.34 - y) / (size * 0.5)) ** 2
    skirt = (((x - cx) / (size * 0.44)) ** 2 + ((y - size * 0.72) / (size * 0.14)) ** 2) ** 0.5
    lit = min(cap, stem, skirt)
    if lit < 1.0:
        edge = max(0.0, 1.0 - lit)
        rgb = mix((196, 176, 160), (92, 78, 74), min(1.0, edge * 1.9))
        if edge > 0.72:
            rgb = mix(rgb, (255, 226, 176), (edge - 0.72) * 3.0)
        return rgb + (255,)
    if fy < 0.30 and h(x, y, 3) % 29 == 0:
        return (255, 246, 220, 210)
    return tuple(int(c) for c in top) + (255,)


TEXTURES = {
    "block/nuke_standard.png": metal("steel"),
    "block/nuke_standard_top.png": top_tex("standard"),
    "block/nuke_standard_console.png": console_tex("standard"),
    "block/nuke_little_boy.png": metal("steel"),
    "block/nuke_little_boy_top.png": top_tex("little_boy"),
    "block/nuke_little_boy_console.png": console_tex("little_boy"),
    "block/nuke_fat_man.png": metal("cast"),
    "block/nuke_fat_man_top.png": top_tex("fat_man"),
    "block/nuke_fat_man_console.png": console_tex("fat_man"),
    "block/nuke_tsar_bomba.png": metal("cast"),
    "block/nuke_tsar_bomba_top.png": top_tex("tsar_bomba"),
    "block/nuke_tsar_bomba_console.png": console_tex("tsar_bomba"),
    "block/vitrified_sand.png": vitrified,
    "block/heated_stone.png": heated,
    "block/heated_stone_top.png": heated_top,
    "block/scorched_dirt.png": scorched,
    "block/scorched_dirt_top.png": scorched,
    "block/charred_log.png": bark,
    "block/charred_log_top.png": rings,
    "item/hazmat_goggles.png": goggles,
    "item/geiger_counter.png": counter,
    "item/remote_detonator.png": detonator,
    "item/iodine_tablet.png": tablet,
}


def main() -> int:
    written = 0
    for name, fn in TEXTURES.items():
        write_png(os.path.join(TEX, name), 16, fn)
        written += 1
    # Entity overlays: 32x32 is the size the renderers assume (they UV-map 0..1 over the whole
    # texture, so any square power of two works; 32 keeps the soft gradients from banding).
    for name, fn in (("entity/glow.png", radial(32)),
                     ("entity/cloud_puff.png", puff(32)),
                     ("entity/ash_puff.png", puff(16))):
        write_png(os.path.join(TEX, name), 32 if "ash" not in name else 16, fn)
        written += 1
    write_png(os.path.join(ROOT, "src", "main", "resources", "assets", "doomsday", "icon.png"),
              128, lambda y, x: icon(y, x, 128))
    written += 1
    print(f"wrote {written} pngs under {os.path.relpath(TEX, ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
