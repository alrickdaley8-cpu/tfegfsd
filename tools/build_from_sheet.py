#!/usr/bin/env python3
"""
build_from_sheet.py - Assemble the Saturn sprite set from a generated/AI
sprite sheet (16 figures on a flat background), slice it into figures, map each
figure to its semantic animation slot, derive the remaining frames, and rebuild
Saturn.sff.

This is a purpose-built driver that reuses the palette/frame/effect machinery
from tools/ingest_sprite.py.

Usage:
    python3 tools/build_from_sheet.py --input chars/Saturn/source/generated_sheet.png [--preview]

If your sheet uses a different layout, edit SHEET_MAP (below) or drop the
sliced cell PNGs into chars/Saturn/source/cells/ yourself (cell_01.png ...
cell_16.png in reading order).
"""

import argparse
import json
import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import ingest_sprite as ing  # noqa: E402

OUT_DIR = os.path.join(ROOT, "chars", "Saturn", "_build", "sprites")
SFF_TOOL = os.path.join(ROOT, "tools", "build_sff.py")

# ---------------------------------------------------------------------------
# Semantic mapping: reading-order sheet cell (1-based) -> (group, index) slot.
# This matches the 16-pose prompt used to generate the sheet:
#   1 idle, 2 walking, 3 crouch, 4 jump, 5 block, 6 hit-reel, 7 light punch,
#   8 heavy claw, 9 low sweep, 10 uppercut, 11 cane thrust, 12 dark orb,
#   13 shockwave, 14 teleport, 15 victory, 16 knocked down.
# ---------------------------------------------------------------------------
SHEET_MAP = {
    1: (0, 0),    # idle stance
    2: (0, 2),    # walk A
    3: (0, 4),    # crouch
    4: (0, 5),    # jump
    5: (0, 8),    # guard
    6: (0, 10),   # hurt stand
    7: (0, 20),   # 5LP
    8: (0, 22),   # 5HP claw
    9: (0, 23),   # 5LK sweep
    10: (0, 32),  # 2HP launcher
    11: (0, 26),  # 4HP cane thrust
    12: (0, 62),  # gravity orb
    13: (0, 61),  # shockwave
    14: (0, 63),  # teleport
    15: (0, 51),  # victory
    16: (0, 13),  # knocked down
}

# Every slot NOT in SHEET_MAP is derived from the nearest real figure. For each
# of those we choose a source cell and a transform recipe (see ingest_sprite).
DERIVE = {
    (0, 1):  (1, "bob"),
    (0, 3):  (2, "walk_b"),
    (0, 6):  (4, "fall"),
    (0, 11): (3, "hurt_c"),
    (0, 12): (4, "hurt"),
    (0, 21): (7, "atk"),
    (0, 24): (8, "atk"),
    (0, 25): (8, "atk"),
    (0, 30): (3, "atk_c"),
    (0, 31): (3, "atk_c"),
    (0, 40): (4, "atk_a"),
    (0, 41): (4, "atk_a"),
    (0, 42): (4, "atk_a"),
    (0, 50): (1, "idle"),
    (0, 52): (1, "walk_a"),
    (0, 60): (8, "sp_claw"),
    (0, 64): (13, "sp_burst"),
    (0, 65): (8, "sp_claw"),
    (0, 70): (12, "sp_beam"),
    (0, 71): (8, "sp_claw"),
    (0, 72): (12, "sp_field"),
    (0, 73): (13, "sp_field"),
    (0, 80): (1, "aura"),
    (0, 81): (1, "aura"),
    (0, 82): (1, "trans"),
}


def remove_bg(img, bg_thresh=60):
    """Flood-fill the flat background to transparent from the borders."""
    img = img.convert("RGBA")
    a = np.array(img)
    if a[..., 3].min() > 0:
        bg = tuple(np.array(a[0, 0, :3]).astype(int))
        rgb = np.array(img.convert("RGB")).astype(int)
        mask = np.abs(rgb - np.array(bg)).sum(axis=2) < bg_thresh
        out = np.array(img)
        h, w = mask.shape
        seen = np.zeros((h, w), bool)
        dq = deque()
        for x in range(w):
            if mask[0, x] and not seen[0, x]:
                seen[0, x] = True; dq.append((0, x))
            if mask[h - 1, x] and not seen[h - 1, x]:
                seen[h - 1, x] = True; dq.append((h - 1, x))
        for y in range(h):
            if mask[y, 0] and not seen[y, 0]:
                seen[y, 0] = True; dq.append((y, 0))
            if mask[y, w - 1] and not seen[y, w - 1]:
                seen[y, w - 1] = True; dq.append((y, w - 1))
        while dq:
            y, x = dq.popleft()
            for ny, nx in ((y-1, x), (y+1, x), (y, x-1), (y, x+1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    dq.append((ny, nx))
        out[seen, 3] = 0
        return Image.fromarray(out)
    return img


def slice_sheet(src):
    """Connected-component figure extraction. Returns 16 RGBA cells in reading
    order (top-left -> bottom-right)."""
    src = remove_bg(src)
    a = np.array(src)
    mask = a[..., 3] > 8
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    cid = 0
    for y in range(h):
        for x in range(w):
            if mask[y, x] and lab[y, x] == 0:
                cid += 1
                dq = deque([(y, x)]); lab[y, x] = cid
                while dq:
                    cy, cx = dq.popleft()
                    for ny, nx in ((cy-1, cx), (cy+1, cx), (cy, cx-1), (cy, cx+1)):
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and lab[ny, nx] == 0:
                            lab[ny, nx] = cid
                            dq.append((ny, nx))
    comps = []
    for i in range(1, cid + 1):
        ys, xs = np.where(lab == i)
        comps.append((len(ys), xs.min(), ys.min(), xs.max() + 1, ys.max() + 1,
                      float(xs.mean()), float(ys.mean())))
    # take the 16 largest as the figures
    comps.sort(key=lambda c: -c[0])
    figures = comps[:16]
    # reading order: sort by centroid y then x
    figures.sort(key=lambda c: (c[6], c[5]))
    cells = []
    for sz, x0, y0, x1, y1, cx, cy in figures:
        pad = 4
        x0 = max(0, x0 - pad); y0 = max(0, y0 - pad)
        x1 = min(w, x1 + pad); y1 = min(h, y1 + pad)
        cells.append(src.crop((x0, y0, x1, y1)))
    return cells


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--cells-dir", default=os.path.join(ROOT, "chars", "Saturn", "source", "cells"))
    args = ap.parse_args()

    # cells: from a directory of pre-sliced cell_XX.png, or slice the sheet
    cells_dir = args.cells_dir
    if os.path.isdir(cells_dir) and any(f.endswith(".png") for f in os.listdir(cells_dir)):
        cells = []
        for i in range(1, 17):
            p = os.path.join(cells_dir, f"cell_{i:02d}.png")
            cells.append(remove_bg(Image.open(p)) if os.path.exists(p) else None)
        print(f"[build_from_sheet] using {sum(c is not None for c in cells)}/16 cells from {cells_dir}")
    else:
        cells = slice_sheet(Image.open(args.input))
        print(f"[build_from_sheet] sliced {len(cells)} figures from {args.input}")

    base = cells[0]
    if base is None:
        raise SystemExit("error: no base figure (cell 1) available")

    to_indexed, pal_img, palettes = ing.build_palettes(base)
    vfx = ing.make_vfx()

    cw, ch = 128, 168
    ax, ay = 64, 160

    def normalize(frame):
        f = ing.trim(frame)
        maxw, maxh = int(cw * 0.92), int(ch * 0.96)
        ratio = min(maxw / f.width, maxh / f.height)
        if ratio < 1.0:
            f = ing._resize(f, (max(1, int(f.width * ratio)), max(1, int(f.height * ratio))))
        elif ratio > 1.01:
            f = ing._resize(f, (int(f.width * ratio), int(f.height * ratio)))
        canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
        canvas.paste(f, (ax - f.width // 2, ay - f.height), f)
        return canvas

    # assemble real frames first, then derived
    real = {slot: cells[i - 1] for i, slot in SHEET_MAP.items() if cells[i - 1] is not None}
    frames = {}
    labels = {}
    for slot in ing.BODY_POSES:
        if slot in real:
            frames[slot] = normalize(real[slot])
            labels[slot] = "sheet"
        elif slot in DERIVE:
            src_cell, recipe = DERIVE[slot]
            src = real.get(SHEET_MAP.get(src_cell), base)
            img, kind = ing.derive_frame(src, recipe)
            frames[slot] = normalize(img)
            labels[slot] = kind
        else:
            img, kind = ing.derive_frame(base, "idle")
            frames[slot] = normalize(img)
            labels[slot] = kind

    os.makedirs(OUT_DIR, exist_ok=True)
    manifest = []
    for slot in sorted(frames):
        g, i = slot
        pimg, _ = to_indexed(frames[slot])
        fn = f"{g}_{i}.png"
        pimg.save(os.path.join(OUT_DIR, fn), palette=pal_img)
        kind = labels[slot]
        manifest.append({"group": g, "index": i, "file": fn,
                         "w": cw, "h": ch, "axis": [ax, ay],
                         "kind": kind, "placeholder": kind in ("placeholder",)})

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

    n_sheet = sum(1 for k in labels.values() if k == "sheet")
    print(f"[build_from_sheet] wrote {len(manifest)} sprites "
          f"({n_sheet} from sheet art, {len(labels)-n_sheet} derived) + 4 palettes")

    if args.preview:
        _preview(manifest)

    import subprocess
    subprocess.run([sys.executable, SFF_TOOL], check=True)


def _preview(manifest):
    cols = 12
    rows = (len(manifest) + cols - 1) // cols
    cell = 176
    sheet = Image.new("RGB", (cols * cell, rows * cell), (20, 16, 30))
    d = ImageDraw.Draw(sheet)
    for i, m in enumerate(manifest):
        img = Image.open(os.path.join(OUT_DIR, m["file"])).convert("RGBA")
        x = (i % cols) * cell + 8
        y = (i // cols) * cell + 8
        bg = Image.new("RGB", img.size, (30, 24, 44))
        bg.paste(img, (0, 0), img)
        sheet.paste(bg, (x, y))
        d.text((x, y + img.size[1] + 6), f"{m['group']},{m['index']}", fill=(255, 255, 255))
    sheet.save(os.path.join(OUT_DIR, "preview.png"))
    print("[build_from_sheet] preview -> " + os.path.join(OUT_DIR, "preview.png"))


if __name__ == "__main__":
    main()
