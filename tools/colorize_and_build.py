#!/usr/bin/env python3
"""
colorize_and_build.py - Turn the extracted line-art figures into colored Saturn
sprites and rebuild Saturn.sff.

The user's sheet is black/dark-navy line art on white. This tool:
  1. removes the white background (flood-fill from the borders),
  2. colorizes each figure: near-black violet outline + white head/beard band +
     deep-violet robe body (region fill below the head),
  3. normalizes every frame onto the shared 128x168 canvas (feet at the axis),
  4. auto-maps the figures (in sheet reading order) to the animation slots,
  5. writes a colorized preview + manifest + Saturn.sff.

The figure->slot mapping is a best-effort guess and is fully documented in
MAPPING.md and in SLOT_MAP below — edit SLOT_MAP and re-run to correct it.

Usage:
    python3 tools/colorize_and_build.py [--preview]
"""

import json
import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import ingest_sprite as ing  # noqa: E402

FIG_DIR = os.path.join(ROOT, "chars", "Saturn", "_build", "figures")
OUT_DIR = os.path.join(ROOT, "chars", "Saturn", "_build", "sprites")
SFF_TOOL = os.path.join(ROOT, "tools", "build_sff.py")

# ---------------------------------------------------------------------------
# Colors (Saturn theme)
# ---------------------------------------------------------------------------
C_OUTLINE = (24, 14, 34)      # near-black violet (keeps the line-art definition)
C_ROBE = (86, 60, 118)        # deep violet robe
C_HEAD = (232, 228, 240)      # white beard / hair / pale skin
HEAD_BAND = 0.30              # top 30% of the figure treated as head/beard

# ---------------------------------------------------------------------------
# Auto mapping: slot (group,index) -> source figure id (f"{row}{col}f{idx}")
# Best-guess based on typical sheet layout:
#   r0 = stances (idle/walk), r1/r2 = attacks, r3 = crouch/jump/guard/hurt,
#   r4 = specials, r5 = supers/hurt/win
# Edit this table and re-run to fix any wrong frame.
# ---------------------------------------------------------------------------
SLOT_MAP = {
    (0, 0): "r0c0_f00",   # idle A
    (0, 1): "r0c0_f01",   # idle B
    (0, 2): "r0c1_f00",   # walk A
    (0, 3): "r0c1_f01",   # walk B
    (0, 4): "r3c0_f00",   # crouch
    (0, 5): "r3c0_f01",   # jump
    (0, 6): "r3c0_f02",   # fall
    (0, 8): "r3c0_f03",   # guard
    (0, 10): "r3c0_f04",  # hurt stand
    (0, 11): "r3c0_f05",  # hurt crouch
    (0, 12): "r3c0_f06",  # hurt air
    (0, 13): "r5c2_f00",  # lie down
    (0, 20): "r1c0_f00",  # 5LP
    (0, 21): "r1c0_f01",  # 5MP
    (0, 22): "r1c0_f02",  # 5HP
    (0, 23): "r1c0_f03",  # 5LK
    (0, 24): "r1c0_f04",  # 5HK
    (0, 25): "r1c0_f05",  # 6HP overhead
    (0, 26): "r1c0_f06",  # 4HP lance
    (0, 30): "r2c0_f00",  # 2LP
    (0, 31): "r2c0_f01",  # 2MP
    (0, 32): "r2c0_f02",  # 2HP launcher
    (0, 40): "r3c1_f00",  # jLP
    (0, 41): "r3c1_f01",  # jMP
    (0, 42): "r3c1_f02",  # jHK
    (0, 50): "r5c0_f00",  # intro
    (0, 51): "r5c0_f01",  # win
    (0, 52): "r5c0_f02",  # taunt
    (0, 60): "r4c0_f00",  # Hell Claw
    (0, 61): "r4c0_f01",  # Haoshoku
    (0, 62): "r4c0_f02",  # Juryoku
    (0, 63): "r4c0_f03",  # Veil Shift
    (0, 64): "r4c0_f04",  # Aku no Hado
    (0, 65): "r4c0_f05",  # Gorosei Judgment
    (0, 70): "r5c1_f00",  # Lv1
    (0, 71): "r5c1_f01",  # Lv2
    (0, 72): "r5c1_f02",  # Lv3
    (0, 73): "r5c1_f03",  # Ultimate
    (0, 80): "r4c1_f00",  # awakened idle A
    (0, 81): "r4c1_f01",  # awakened idle B
    (0, 82): "r4c2_f00",  # transform
}


def remove_bg_and_colorize(rgb_crop):
    """Line art -> colored RGBA: white bg transparent, ink = outline, enclosed
    regions filled with head (top band) or robe colors."""
    a = np.array(rgb_crop).astype(int)
    hh, ww = a.shape[:2]
    ink = a.sum(axis=2) < 540

    # background = non-ink connected to the border
    bg = np.zeros((hh, ww), bool)
    dq = deque()
    for x in range(ww):
        if not ink[0, x] and not bg[0, x]:
            bg[0, x] = True; dq.append((0, x))
        if not ink[hh-1, x] and not bg[hh-1, x]:
            bg[hh-1, x] = True; dq.append((hh-1, x))
    for y in range(hh):
        if not ink[y, 0] and not bg[y, 0]:
            bg[y, 0] = True; dq.append((y, 0))
        if not ink[y, ww-1] and not bg[y, ww-1]:
            bg[y, ww-1] = True; dq.append((y, ww-1))
    while dq:
        y, x = dq.popleft()
        for ny, nx in ((y-1, x), (y+1, x), (y, x-1), (y, x+1)):
            if 0 <= ny < hh and 0 <= nx < ww and not ink[ny, nx] and not bg[ny, nx]:
                bg[ny, nx] = True
                dq.append((ny, nx))

    interior = (~ink) & (~bg)

    # head band based on the ink extent
    ys, xs = np.where(ink)
    if len(ys) == 0:
        top, bot = 0, hh
    else:
        top, bot = ys.min(), ys.max()
    head_line = top + HEAD_BAND * max(1, (bot - top))

    out = np.zeros((hh, ww, 4), np.uint8)
    # outline
    out[ink] = C_OUTLINE + (255,)
    # head/beard (interior above head line)
    head_region = interior & (np.arange(hh)[:, None] < head_line)
    out[head_region] = C_HEAD + (255,)
    # robe (interior below head line)
    robe_region = interior & (~head_region)
    out[robe_region] = C_ROBE + (255,)
    return Image.fromarray(out, "RGBA")


def main():
    figs = json.load(open(os.path.join(FIG_DIR, "figures.json")))
    byid = {}
    for f in figs:
        rid = f"r{f['row']}c{f['col']}_f{f['frame']:02d}"
        byid[rid] = f

    # resolve and report missing slots
    missing = [rid for rid in SLOT_MAP.values() if rid not in byid]
    if missing:
        print("WARNING: missing figure ids:", missing)

    colored = {}
    for rid in set(SLOT_MAP.values()):
        if rid not in byid:
            continue
        f = byid[rid]
        crop = Image.open(os.path.join(FIG_DIR, f["file"])).convert("RGB")
        colored[rid] = remove_bg_and_colorize(crop)

    # build a colorized contact sheet for review
    os.makedirs(FIG_DIR, exist_ok=True)
    ids = sorted(colored)
    cols = 10
    rows = (len(ids) + cols - 1) // cols
    cell = 180
    sheet = Image.new("RGB", (cols * cell, rows * cell), (40, 30, 60))
    d = ImageDraw.Draw(sheet)
    for i, rid in enumerate(ids):
        img = colored[rid]
        img = img.resize((img.width * 2, img.height * 2), Image.NEAREST)
        x = (i % cols) * cell + 8
        y = (i // cols) * cell + 8
        sheet.paste(img, (x, y), img)
        d.text((x, y + img.height + 4), rid, fill=(255, 255, 255))
    sheet.save(os.path.join(FIG_DIR, "colorized_contact_sheet.png"))
    print(f"[colorize] {len(ids)} unique figures colorized; contact sheet -> "
          f"{os.path.join(FIG_DIR, 'colorized_contact_sheet.png')}")

    # normalize onto the canvas
    cw, ch = 128, 168
    ax, ay = 64, 160
    scale = 0.95

    def normalize(img):
        w, h = img.size
        nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
        img = img.resize((nw, nh), Image.NEAREST)
        canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
        canvas.paste(img, (ax - nw // 2, ay - nh), img)
        return canvas

    # palette from the first colorized frame (all frames share the same palette)
    first = colored[SLOT_MAP[(0, 0)]] if (0, 0) in SLOT_MAP else None
    if first is None:
        raise SystemExit("no frames to build from")
    to_indexed, pal_img, palettes = ing.build_palettes(first)
    vfx = ing.make_vfx()

    os.makedirs(OUT_DIR, exist_ok=True)
    manifest = []
    for slot in sorted(SLOT_MAP):
        rid = SLOT_MAP[slot]
        if rid not in colored:
            continue
        g, i = slot
        frame = normalize(colored[rid])
        pimg, _ = to_indexed(frame)
        fn = f"{g}_{i}.png"
        pimg.save(os.path.join(OUT_DIR, fn), palette=pal_img)
        manifest.append({"group": g, "index": i, "file": fn,
                         "w": cw, "h": ch, "axis": [ax, ay],
                         "kind": "sheet", "placeholder": False})

    for idx, img in vfx.items():
        fn = f"100_{idx}.png"
        pimg, _ = to_indexed(img)
        pimg.save(os.path.join(OUT_DIR, fn), palette=pal_img)
        manifest.append({"group": 100, "index": idx, "file": fn,
                         "w": img.width, "h": img.height,
                         "axis": [img.width // 2, img.height // 2],
                         "kind": "vfx", "placeholder": False})

    manifest.sort(key=lambda m: (m["group"], m["index"]))
    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump({"palettes": palettes, "sprites": manifest}, f, indent=2)

    print(f"[colorize] wrote {len(manifest)} sprites + 4 palettes")

    import subprocess
    subprocess.run([sys.executable, SFF_TOOL], check=True)


if __name__ == "__main__":
    main()
