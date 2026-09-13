#!/usr/bin/env python3
"""
ingest_sprite.py - Convert the user's Saturn sprite into the character's full
sprite set (body frames + effects), palettes, and manifest, then rebuild
Saturn.sff.

WHAT IT DOES
  * Loads a source image (single full-body render OR a sprite sheet).
  * Auto-removes a solid background if the image has no alpha (flood-fill from
    the borders), preserving transparency otherwise.
  * Detects single vs. sheet (aspect ratio / disconnected blobs) with manual
    --cols/--rows overrides.
  * Normalizes every frame onto the fixed body canvas (128x168, axis 64,160)
    so frames never jitter. Pixel art is NEVER upscaled with blurry
    interpolation (NEAREST for upscale, LANCZOS for downscale only).
  * Derives the full animation frame set from the supplied art. Frames that are
    simple transforms of the source (idle bob, crouch, jump, guard, hurt) are
    marked kind=derived. Attack/special frames add an original effect overlay
    and are marked placeholder=true so they can be replaced with real keyframes
    later. Nothing is silently fabricated.
  * Builds a 256-color indexed palette from the sprite + a fixed effect ramp,
    then three alternate palettes (gloom / void / elder).
  * Writes chars/Saturn/_build/sprites/{0_0..0_82,100_0..100_10}.png + manifest.json
    + preview.png, then calls tools/build_sff.py.

USAGE
    python3 tools/ingest_sprite.py --input path/to/saturn.png [options]

OPTIONS
    --input PATH      source sprite or sprite sheet (required)
    --mode auto|single|sheet   frame layout (default: auto)
    --cols N          grid columns for a sheet (overrides auto-detect)
    --rows N          grid rows for a sheet
    --canvas WxH      body canvas size (default 128x168)
    --axis X,Y        feet-center axis (default 64,160)
    --no-build        write sprites but do NOT rebuild Saturn.sff
    --preview         also write preview.png
"""

import argparse
import io
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "chars", "Saturn", "_build", "sprites")
SFF_TOOL = os.path.join(ROOT, "tools", "build_sff.py")

# ---------------------------------------------------------------------------
# Canonical pose numbering (must match chars/Saturn/Saturn.air)
# ---------------------------------------------------------------------------
# Each entry: (group, index) -> recipe. Recipes:
#   "idle"  : base frame, untouched
#   "bob"   : base shifted down 1 px (breathing)
#   "walk_a": base leaned back  2 deg
#   "walk_b": base leaned fwd   2 deg
#   "crouch": base squashed vertically
#   "jump"  : base lifted
#   "fall"  : base dropped
#   "guard" : base leaned back 3 deg
#   "hurt"  : base leaned fwd + red tint
#   "hurt_c": squash + red tint
#   "lie"   : squash to a prone block (placeholder)
#   "atk"   : base leaned fwd + claw streak overlay (placeholder attack)
#   "atk_c" : crouch + claw overlay
#   "atk_a" : air (lifted) + claw overlay
#   "sp_orb" / "sp_ring" / "sp_beam" / "sp_burst" / "sp_field" : base + big fx
#   "aura"  : base + violet aura tint (awakened)
#   "trans" : base + bright aura
BODY_POSES = {
    (0, 0):   "idle",
    (0, 1):   "bob",
    (0, 2):   "walk_a",
    (0, 3):   "walk_b",
    (0, 4):   "crouch",
    (0, 5):   "jump",
    (0, 6):   "fall",
    (0, 8):   "guard",
    (0, 10):  "hurt",
    (0, 11):  "hurt_c",
    (0, 12):  "hurt",
    (0, 13):  "lie",
    (0, 20):  "atk",
    (0, 21):  "atk",
    (0, 22):  "atk",
    (0, 23):  "atk",
    (0, 24):  "atk",
    (0, 25):  "atk",
    (0, 26):  "atk",
    (0, 30):  "atk_c",
    (0, 31):  "atk_c",
    (0, 32):  "atk_c",
    (0, 40):  "atk_a",
    (0, 41):  "atk_a",
    (0, 42):  "atk_a",
    (0, 50):  "idle",
    (0, 51):  "idle",
    (0, 52):  "walk_a",
    (0, 60):  "sp_claw",
    (0, 61):  "sp_ring",
    (0, 62):  "sp_field",
    (0, 63):  "sp_burst",
    (0, 64):  "sp_burst",
    (0, 65):  "sp_claw",
    (0, 70):  "sp_beam",
    (0, 71):  "sp_claw",
    (0, 72):  "sp_field",
    (0, 73):  "sp_field",
    (0, 80):  "aura",
    (0, 81):  "aura",
    (0, 82):  "trans",
}

# Effect ramp indices (fixed tail of the 256-color palette). VFX are drawn using
# ONLY these colors so they survive palette quantization exactly.
FX_BASE = 200            # indices 200..255 are the effect ramp (56 colors)
FX_RAMP = [
    # white -> violet -> magenta -> red -> gold, used by the effect painters
    (255, 255, 255), (238, 236, 255), (214, 200, 255), (186, 160, 255),
    (160, 124, 255), (136, 92, 244), (118, 66, 226), (104, 48, 200),
    (92, 34, 168), (80, 24, 138), (66, 16, 108), (52, 10, 82),
    (255, 150, 150), (236, 96, 96), (206, 48, 52), (172, 28, 34),
    (140, 20, 26), (108, 14, 20), (255, 200, 110), (244, 170, 70),
    (222, 138, 44), (188, 106, 30), (152, 80, 22),
]


# ---------------------------------------------------------------------------
# Image helpers
# ---------------------------------------------------------------------------
def _resize(img, size):
    """NEAREST for upscale, LANCZOS for downscale (no blurry pixel art)."""
    w, h = size
    if w >= img.width and h >= img.height:
        return img.resize((w, h), Image.NEAREST)
    return img.resize((w, h), Image.LANCZOS)


def remove_background(img):
    """If the image has opaque borders, flood-fill a solid background to
    transparent. Returns an RGBA image."""
    img = img.convert("RGBA")
    a = np.array(img)
    if a[..., 3].min() > 0:
        # fully opaque: treat the border color as background
        bg = tuple(a[0, 0, :3])
        img = img.convert("RGB")
        # build a mask of "close to bg" pixels via numpy
        rgb = np.array(img).astype(int)
        dist = np.abs(rgb - np.array(bg)).sum(axis=2)
        mask = dist < 30
        out = np.array(img.convert("RGBA"))
        # flood fill from borders through the mask so interior bg-colored
        # pixels (e.g. between limbs) are preserved unless connected to border
        from collections import deque
        h, w = mask.shape
        seen = np.zeros((h, w), bool)
        dq = deque()
        for x in range(w):
            if mask[0, x] and not seen[0, x]:
                seen[0, x] = True; dq.append((0, x))
            if mask[h-1, x] and not seen[h-1, x]:
                seen[h-1, x] = True; dq.append((h-1, x))
        for y in range(h):
            if mask[y, 0] and not seen[y, 0]:
                seen[y, 0] = True; dq.append((y, 0))
            if mask[y, w-1] and not seen[y, w-1]:
                seen[y, w-1] = True; dq.append((y, w-1))
        while dq:
            y, x = dq.popleft()
            for ny, nx in ((y-1, x), (y+1, x), (y, x-1), (y, x+1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    dq.append((ny, nx))
        out[seen, 3] = 0
        return Image.fromarray(out)
    return img


def content_bbox(img):
    a = np.array(img)
    if a.shape[-1] == 4:
        ys, xs = np.where(a[..., 3] > 8)
    else:
        ys, xs = np.where(a.any(axis=2))
    if len(xs) == 0:
        return (0, 0, img.width, img.height)
    return (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)


def trim(img, pad=2):
    x0, y0, x1, y1 = content_bbox(img)
    x0 = max(0, x0 - pad); y0 = max(0, y0 - pad)
    x1 = min(img.width, x1 + pad); y1 = min(img.height, y1 + pad)
    return img.crop((x0, y0, x1, y1))


# ---------------------------------------------------------------------------
# Palette construction
# ---------------------------------------------------------------------------
def build_palettes(base_rgba):
    """Returns (palette_pil, palettes) where palettes maps name -> 256-entry
    [r,g,b] lists and palette_pil is a PIL P-mode palette image (used to map
    RGBA sprites to indices)."""
    a = np.array(base_rgba)
    opaque = a[..., 3] > 128
    if not opaque.any():
        raise SystemExit("error: source sprite has no opaque pixels")
    colors = a[opaque][:, :3]
    # quantize to at most (FX_BASE - 1) colors so indices 0..199 hold body colors
    from PIL import Image as _I
    rgb_img = Image.fromarray(np.where(opaque[..., None], a[..., :3], 0).astype(np.uint8))
    q = rgb_img.quantize(colors=FX_BASE - 1, method=Image.MEDIANCUT, dither=Image.NONE)
    qpal = q.getpalette()
    ncols = len(qpal) // 3
    body_colors = [tuple(qpal[i*3:i*3+3]) for i in range(ncols)]

    full = [(0, 0, 0)] + body_colors
    while len(full) < FX_BASE:
        full.append((0, 0, 0))
    full += FX_RAMP
    while len(full) < 256:
        full.append((0, 0, 0))
    full = full[:256]

    # build a PIL palette image for mapping
    pal_bytes = bytearray()
    for r, g, b in full:
        pal_bytes += bytes((r, g, b))
    pal_img = Image.new("P", (1, 1))
    pal_img.putpalette(pal_bytes)

    # map helper: RGBA -> P index (transparent -> 0)
    body_lookup = {c: i + 1 for i, c in enumerate(body_colors)}
    fx_lookup = {c: FX_BASE + i for i, c in enumerate(FX_RAMP)}

    def to_indexed(rgba):
        arr = np.array(rgba)
        h, w = arr.shape[:2]
        idx = np.zeros((h, w), dtype=np.uint8)
        opaque = arr[..., 3] > 128
        rgb = arr[opaque][:, :3]
        keys = np.array(list(body_lookup.keys()) + list(fx_lookup.keys()), dtype=np.int32)
        vals = np.array(list(body_lookup.values()) + list(fx_lookup.values()), dtype=np.uint8)
        # nearest color match among known palette colors (exact for body quant
        # colors and FX ramp colors)
        if len(rgb):
            # vectorized nearest via distance
            dist = np.abs(keys[None, :, :].astype(np.int32) - rgb[:, None, :].astype(np.int32)).sum(axis=2)
            mapped = vals[np.argmin(dist, axis=1)]
            idx[opaque] = mapped
        return Image.fromarray(idx, mode="P"), pal_img

    # recolor helper
    def recolor(transform):
        out = list(full)
        for i in range(1, FX_BASE):
            out[i] = transform(out[i])
        return out

    palettes = {
        "default": full,
        "gloom": recolor(lambda c: (int(c[0]*0.7), int(c[1]*0.72), int(c[2]*0.85))),
        "void": recolor(lambda c: (min(255, int(c[0]*0.85 + 18)), int(c[1]*0.65), min(255, int(c[2]*1.15)))),
        "elder": recolor(lambda c: (min(255, int(c[0]*1.10)), int(c[1]*0.95), int(c[2]*0.7))),
    }
    return to_indexed, pal_img, palettes


# ---------------------------------------------------------------------------
# Transform recipes (operate on RGBA; final save maps through the palette)
# ---------------------------------------------------------------------------
def _tint(img, mul):
    a = np.array(img)
    out = a.copy()
    out[..., :3] = (out[..., :3].astype(np.int32) * np.array(mul) // 255).astype(np.uint8)
    return Image.fromarray(out)


def _lean(img, deg):
    return img.rotate(-deg, resample=Image.NEAREST, expand=True)


def _squash(img, factor):
    w, h = img.size
    return img.resize((w, max(1, int(h * factor))), Image.NEAREST)


def _lift(img, dy):
    w, h = img.size
    out = Image.new("RGBA", (w, h + abs(dy) * 2), (0, 0, 0, 0))
    out.paste(img, (0, abs(dy) * 2 + dy if dy < 0 else dy))
    return out


def _draw_streak(img, color, dx=0, dy=0):
    """Overlay 3 claw streaks pointing right (attack direction)."""
    d = ImageDraw.Draw(img)
    w, h = img.size
    cx = int(w * 0.62) + dx
    cy = int(h * 0.42) + dy
    for i, ln in enumerate((int(w*0.30), int(w*0.24), int(w*0.18))):
        d.line([cx, cy, cx + ln, cy - int(ln*0.45) - i*8], fill=color + (255,), width=3 + i)
    return img


def _draw_ring(img, color, r=None):
    d = ImageDraw.Draw(img)
    w, h = img.size
    cx, cy = int(w*0.5), int(h*0.62)
    r = r or int(w*0.34)
    d.ellipse([cx-r, cy-r//2, cx+r, cy+r//2], outline=color + (255,), width=4)
    return img


def _draw_orb(img, color, r=None):
    d = ImageDraw.Draw(img)
    w, h = img.size
    cx, cy = int(w*0.5), int(h*0.35)
    r = r or int(w*0.20)
    d.ellipse([cx-r, cy-r, cx+r, cy+r], fill=color + (200,))
    return img


def _draw_beam(img, color):
    d = ImageDraw.Draw(img)
    w, h = img.size
    d.polygon([(int(w*0.4), int(h*0.30)), (w, int(h*0.40)),
               (w, int(h*0.46)), (int(w*0.4), int(h*0.36))], fill=color + (220,))
    return img


# ---------------------------------------------------------------------------
# Frame derivation
# ---------------------------------------------------------------------------
def derive_frame(base, recipe):
    """Apply a recipe transform to the base RGBA sprite; returns RGBA + label."""
    if recipe == "idle":
        return base, "derived"
    if recipe == "bob":
        return _lift(base, 1), "derived"
    if recipe == "walk_a":
        return _lean(base, -2), "derived"
    if recipe == "walk_b":
        return _lean(base, 2), "derived"
    if recipe == "crouch":
        return _squash(base, 0.78), "derived"
    if recipe == "jump":
        return _lift(base, -4), "derived"
    if recipe == "fall":
        return _lift(base, 3), "derived"
    if recipe == "guard":
        return _lean(base, -3), "derived"
    if recipe == "hurt":
        return _tint(_lean(base, 4), (255, 200, 200)), "derived"
    if recipe == "hurt_c":
        return _tint(_squash(base, 0.78), (255, 200, 200)), "derived"
    if recipe == "lie":
        return _squash(base, 0.32), "placeholder"
    if recipe == "atk":
        return _draw_streak(_lean(base, 5), (214, 200, 255)), "placeholder"
    if recipe == "atk_c":
        return _draw_streak(_squash(base, 0.8), (214, 200, 255)), "placeholder"
    if recipe == "atk_a":
        return _draw_streak(_lift(base, -4), (214, 200, 255)), "placeholder"
    if recipe == "sp_claw":
        return _draw_streak(_lean(base, 6), (255, 150, 150)), "placeholder"
    if recipe == "sp_ring":
        return _draw_ring(base, (214, 200, 255)), "placeholder"
    if recipe == "sp_field":
        return _draw_orb(base, (160, 124, 255)), "placeholder"
    if recipe == "sp_burst":
        return _draw_ring(base, (214, 200, 255), r=None), "placeholder"
    if recipe == "sp_beam":
        return _draw_beam(base, (214, 200, 255)), "placeholder"
    if recipe == "aura":
        return _tint(base, (200, 180, 255)), "derived"
    if recipe == "trans":
        return _tint(base, (255, 230, 200)), "derived"
    return base, "derived"


def make_vfx():
    """Generate the 11 effect sprites (group 100) in the effect-ramp colors."""
    specs = {
        0: ("streak", 96, 96),
        1: ("orb", 64, 64),
        2: ("ring", 128, 64),
        3: ("impact", 96, 96),
        4: ("aura", 96, 96),
        5: ("smoke", 64, 64),
        6: ("beam", 128, 64),
        7: ("spark", 32, 32),
        8: ("field", 96, 128),
        9: ("eyeflash", 32, 32),
        10: ("flash", 8, 8),
    }
    out = {}
    for idx, (kind, w, h) in specs.items():
        img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        c = (214, 200, 255)
        if kind == "streak":
            d.line([0, h, w, 0], fill=c + (255,), width=6)
            d.line([0, h//2, w//2, 0], fill=(255, 150, 150) + (255,), width=4)
        elif kind == "orb":
            r = w // 2 - 4
            d.ellipse([4, 4, w-4, h-4], fill=(92, 34, 168) + (230,))
            d.ellipse([w//4, h//4, 3*w//4, 3*h//4], fill=(160, 124, 255) + (255,))
        elif kind == "ring":
            d.ellipse([2, h//2-2, w-2, h//2+2], outline=c + (255,), width=3)
            d.ellipse([w//4, 4, 3*w//4, h-4], outline=(136, 92, 244) + (255,), width=2)
        elif kind == "impact":
            d.ellipse([0, 0, w, h], outline=(255, 255, 255) + (255,), width=4)
            d.ellipse([w//4, h//4, 3*w//4, 3*h//4], fill=(255, 200, 110) + (200,))
        elif kind == "aura":
            d.ellipse([0, 0, w, h], outline=c + (255,), width=3)
            d.ellipse([w//6, h//6, 5*w//6, 5*h//6], outline=(136, 92, 244) + (255,), width=2)
        elif kind == "smoke":
            for i in range(5):
                d.ellipse([i*8, h//2 - 4 - i*2, i*8 + 16, h//2 + 12], fill=(80, 24, 138) + (180,))
        elif kind == "beam":
            d.polygon([(0, h//2 - 4), (w, 0), (w, h), (0, h//2 + 4)], fill=c + (220,))
            d.polygon([(0, h//2 - 2), (w, 0), (w, h), (0, h//2 + 2)], fill=(255, 255, 255) + (255,))
        elif kind == "spark":
            d.line([0, 0, w, h], fill=(255, 255, 255) + (255,), width=2)
            d.line([0, h, w, 0], fill=(255, 200, 110) + (255,), width=2)
        elif kind == "field":
            d.ellipse([0, 0, w, h], outline=c + (255,), width=4)
            d.ellipse([w//4, h//4, 3*w//4, 3*h//4], outline=(136, 92, 244) + (255,), width=3)
        elif kind == "eyeflash":
            d.ellipse([2, 2, w-2, h-2], fill=(255, 150, 150) + (255,))
        elif kind == "flash":
            d.rectangle([0, 0, w, h], fill=(255, 255, 255) + (255,))
        out[idx] = img
    return out


# ---------------------------------------------------------------------------
# Sheet detection / splitting
# ---------------------------------------------------------------------------
def detect_cells(img, cols=None, rows=None):
    """Split a sprite sheet into cells. Returns list of RGBA images."""
    img = remove_background(img)
    if cols and rows:
        w, h = img.size
        cw, ch = w // cols, h // rows
        return [img.crop((x*cw, y*ch, x*cw+cw, y*ch+ch)) for y in range(rows) for x in range(cols)]
    # auto: find columns via vertical whitespace between blobs
    a = np.array(img)
    alpha = a[..., 3] > 8
    col_has = alpha.any(axis=0)
    # find gaps (columns fully empty)
    gaps = []
    in_blob = False
    start = 0
    cells = []
    for x in range(img.width):
        if col_has[x] and not in_blob:
            start = x; in_blob = True
        elif not col_has[x] and in_blob:
            cells.append((start, x)); in_blob = False
    if in_blob:
        cells.append((start, img.width))
    if len(cells) >= 2:
        return [trim(img.crop((x0, 0, x1, img.height))) for x0, x1 in cells]
    # fall back: single cell = whole (trimmed) image
    return [trim(img)]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--mode", choices=["auto", "single", "sheet"], default="auto")
    ap.add_argument("--cols", type=int)
    ap.add_argument("--rows", type=int)
    ap.add_argument("--canvas", default="128x168")
    ap.add_argument("--axis", default="64,160")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(args.input):
        raise SystemExit(f"error: input not found: {args.input}")

    cw, ch = (int(v) for v in args.canvas.split("x"))
    ax, ay = (int(v) for v in args.axis.split(","))

    src = Image.open(args.input)
    src = remove_background(src)

    # --- frame sources ---
    if args.mode == "sheet" or (args.mode == "auto" and (args.cols or args.rows
                               or src.width >= src.height * 1.5)):
        cells = detect_cells(src, args.cols, args.rows)
        print(f"[ingest] sheet mode: {len(cells)} cells detected")
    else:
        cells = [trim(src)]
        print("[ingest] single-render mode: 1 base sprite")

    # The first cell is the canonical base; extra cells map to poses in
    # ascending order (idle, idle B, walk A, walk B, ...).
    base = cells[0]
    extra = cells[1:]

    # --- palette ---
    to_indexed, pal_img, palettes = build_palettes(base)

    # --- normalize helper ---
    def normalize(frame):
        f = trim(frame)
        # scale to fit canvas keeping aspect (NEAREST up, LANCZOS down)
        maxw, maxh = int(cw * 0.92), int(ch * 0.96)
        ratio = min(maxw / f.width, maxh / f.height)
        if ratio < 1.0:
            f = _resize(f, (max(1, int(f.width * ratio)), max(1, int(f.height * ratio))))
        elif ratio > 1.01:
            f = _resize(f, (int(f.width * ratio), int(f.height * ratio)))
        canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
        px = ax - f.width // 2
        py = ay - f.height
        canvas.paste(f, (px, py), f)
        return canvas

    # --- build frames ---
    manifest = []
    vfx = make_vfx()
    pose_keys = sorted(BODY_POSES.keys())
    for k, (g, i) in enumerate(pose_keys):
        recipe = BODY_POSES[(g, i)]
        if g == 0 and i == 0 and recipe == "idle":
            frame = base
            kind = "base"
        elif k < len(extra) + 1 and k > 0 and extra:
            # use an explicit sheet cell if one was supplied for this slot
            frame = extra[min(k - 1, len(extra) - 1)]
            kind = "sheet"
        else:
            frame, kind = derive_frame(base, recipe)
        frame = normalize(frame)
        pimg, _ = to_indexed(frame)
        fn = f"{g}_{i}.png"
        pimg.save(os.path.join(OUT_DIR, fn), palette=pal_img)
        manifest.append({"group": g, "index": i, "file": fn,
                         "w": cw, "h": ch, "axis": [ax, ay],
                         "kind": kind, "placeholder": kind in ("placeholder", "sheet")})

    for idx, img in vfx.items():
        fn = f"100_{idx}.png"
        pimg, _ = to_indexed(img)
        pimg.save(os.path.join(OUT_DIR, fn), palette=pal_img)
        manifest.append({"group": 100, "index": idx, "file": fn,
                         "w": img.width, "h": img.height,
                         "axis": [img.width // 2, img.height // 2],
                         "kind": "vfx", "placeholder": False})

    manifest.sort(key=lambda m: (m["group"], m["index"]))
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump({"palettes": palettes, "sprites": manifest}, f, indent=2)

    print(f"[ingest] wrote {len(manifest)} sprites + 4 palettes to {OUT_DIR}")

    if args.preview:
        _preview(manifest)

    if not args.no_build:
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
    print("[ingest] preview -> " + os.path.join(OUT_DIR, "preview.png"))


if __name__ == "__main__":
    main()
