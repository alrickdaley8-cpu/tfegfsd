#!/usr/bin/env python3
"""
build_sff.py - Pack the generated indexed PNGs into a SFF v2.01 sprite file
for IKEMEN GO / M.U.G.E.N 1.x.

Format notes (verified against Ikemen-GO src/image.go):
  * Header is 64 bytes:
       0  12  "ElecbyteSpr\\x00"
      12   4  version: verlo3, verlo2, verlo1, verhi  (2.0.1.0 -> 00 01 00 02)
      16  20  reserved
      36   4  first sprite header offset
      40   4  number of sprites
      44   4  first palette header offset
      48   4  number of palettes
      52   4  lofs  (offset of data region; sprite/palette offsets are relative to this)
      56   4  reserved
      60   4  tofs  (total file size)
  * Sprite header is 28 bytes (IKEMEN GO readHeaderV2 + shofs+=28):
       group u16, number u16, width u16, height u16,
       xaxis i16, yaxis i16, link u16, format u8, coldepth u8,
       dataOffset u32, dataLength u32, palidx u16, flags u16
    We store sprites as RAW 8-bit indexed pixel data (format 0, coldepth 8,
    flags 0 -> offset relative to lofs).
  * Palette header is 16 bytes:
       group u16, index u16, numcolors u16, link u16, offset u32, size u32
    Palette data is size/4 RGBA quads (R,G,B,A little-endian).

Palette layout:
    header 0 = (0,0) default        <- shared copy, not recolored by selection
    header 1 = (1,1) default        <- all sprites reference palidx=1
    header 2 = (1,2), 3 = (1,3), 4 = (1,4)  (selectable recolors)

Usage:
    python3 tools/build_sff.py
"""

import json
import os
import struct

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPRITE_DIR = os.path.join(ROOT, "chars", "Saturn", "_build", "sprites")
OUT_SFF = os.path.join(ROOT, "chars", "Saturn", "Saturn.sff")


def u16(v):
    return struct.pack("<H", v & 0xFFFF)


def i16(v):
    return struct.pack("<h", v & 0xFFFF)


def u32(v):
    return struct.pack("<I", v & 0xFFFFFFFF)


def palette_to_rgba(pal_list):
    """pal_list: list of [r,g,b] or [r,g,b,a]; returns bytes of RGBA quads.
    Index 0 is always forced alpha 0 (transparent); other entries default
    alpha 255 unless an alpha channel is provided."""
    out = bytearray()
    for i, entry in enumerate(pal_list):
        r, g, b = int(entry[0]), int(entry[1]), int(entry[2])
        a = int(entry[3]) if len(entry) >= 4 else 255
        if i == 0:
            a = 0
        out += bytes((r & 0xFF, g & 0xFF, b & 0xFF, a & 0xFF))
    return bytes(out)


def sp_get(sp, key, default):
    """Read a manifest field tolerating both schema spellings."""
    if key == "index":
        return sp.get("index", sp.get("image"))
    if key == "axis":
        v = sp.get("axis")
        if v is None:
            v = [sp.get("ax", 0), sp.get("ay", 0)]
        return v
    return sp.get(key, default)


def main():
    manifest = json.load(open(os.path.join(SPRITE_DIR, "manifest.json")))
    sprites = manifest["sprites"]
    palettes = manifest["palettes"]  # {name: [[r,g,b,a] x256]}

    # Palette records in file order; sprites reference palidx=1 (the (1,1) default)
    palette_entries = [
        ("default", 0, 0),
        ("default", 1, 1),
        ("gloom", 1, 2),
        ("void", 1, 3),
        ("elder", 1, 4),
    ]

    n_sprites = len(sprites)
    n_palettes = len(palette_entries)

    header_size = 64
    sprite_hdr_size = 28  # engine readHeaderV2 + shofs+=28 (group,u16..flags u16)
    pal_hdr_size = 16

    first_sprite_hdr = header_size
    first_pal_hdr = first_sprite_hdr + n_sprites * sprite_hdr_size
    data_start = first_pal_hdr + n_palettes * pal_hdr_size
    lofs = data_start

    # ---- palette data ----
    palette_data_blobs = []
    palette_offsets = []
    cursor = data_start
    for name, g, i in palette_entries:
        blob = palette_to_rgba(palettes[name])
        palette_offsets.append(cursor - lofs)
        palette_data_blobs.append(blob)
        cursor += len(blob)

    # ---- sprite data ----
    sprite_headers = []
    sprite_data_blobs = []
    for sp in sprites:
        path = os.path.join(SPRITE_DIR, sp["file"])
        a = np.array(Image.open(path), dtype=np.uint8)
        w, h = int(sp["w"]), int(sp["h"])
        raw = a.tobytes()
        assert len(raw) == w * h, f"unexpected size for {sp['file']}"
        off = cursor - lofs
        axis = sp_get(sp, "axis", [0, 0])
        hdr = (
            u16(sp["group"]) + u16(sp_get(sp, "index", 0)) +
            u16(w) + u16(h) +
            i16(axis[0]) + i16(axis[1]) +
            u16(0) +                 # link
            bytes((0, 8)) +          # format 0 (raw), coldepth 8
            u32(off) + u32(len(raw)) +
            u16(1) +                 # palidx -> (1,1) default palette
            u16(0)                   # flags: 0 = offset relative to lofs
        )
        assert len(hdr) == sprite_hdr_size
        sprite_headers.append(hdr)
        sprite_data_blobs.append(raw)
        cursor += len(raw)

    tofs = cursor

    # ---- assemble ----
    out = bytearray()
    out += b"ElecbyteSpr\x00"
    out += bytes((0, 1, 0, 2))          # version 2.0.1.0 (alpha honored)
    out += bytes(20)                    # reserved
    out += u32(first_sprite_hdr)
    out += u32(n_sprites)
    out += u32(first_pal_hdr)
    out += u32(n_palettes)
    out += u32(lofs)
    out += u32(0)                       # reserved
    out += u32(tofs)
    assert len(out) == header_size

    for h in sprite_headers:
        out += h
    assert len(out) == first_pal_hdr

    for (name, g, i), off, blob in zip(palette_entries, palette_offsets, palette_data_blobs):
        out += u16(g) + u16(i) + u16(256) + u16(0) + u32(off) + u32(len(blob))
    assert len(out) == data_start

    for blob in palette_data_blobs:
        out += blob
    for blob in sprite_data_blobs:
        out += blob
    assert len(out) == tofs

    os.makedirs(os.path.dirname(OUT_SFF), exist_ok=True)
    with open(OUT_SFF, "wb") as f:
        f.write(out)

    print(f"Wrote {OUT_SFF}: {tofs} bytes, {n_sprites} sprites, {n_palettes} palettes")


if __name__ == "__main__":
    main()
