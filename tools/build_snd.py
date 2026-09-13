#!/usr/bin/env python3
"""
build_snd.py - Pack the generated WAVs into a SND v1 sound file for
IKEMEN GO / M.U.G.E.N.

Format notes (verified against Ikemen-GO src/sound.go):
  * Header is 24 bytes:
       0  12  "ElecbyteSnd\\x00"
      12   2  version      (1)
      14   2  compat ver   (0)
      16   4  number of sounds
      20   4  first subheader offset (24)
  * Sounds are laid out back-to-back. Each entry is a 16-byte subheader
    IMMEDIATELY followed by its WAV data:
       0   4  next subheader offset (0 for the last sound)
       4   4  WAV data length
       8   4  group  (int32)
      12   4  number (int32)
    So next_offset = this_subheader_offset + 16 + wav_length.

Usage:
    python3 tools/build_snd.py
"""

import json
import os
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOUND_DIR = os.path.join(ROOT, "chars", "Saturn", "_build", "sounds")
OUT_SND = os.path.join(ROOT, "chars", "Saturn", "Saturn.snd")


def u16(v):
    return struct.pack("<H", v & 0xFFFF)


def u32(v):
    return struct.pack("<I", v & 0xFFFFFFFF)


def i32(v):
    return struct.pack("<i", v)


def main():
    manifest = json.load(open(os.path.join(SOUND_DIR, "manifest.json")))
    manifest.sort(key=lambda s: (s["group"], s["number"]))

    header_size = 24
    sub_size = 16

    n = len(manifest)
    first_sub = header_size

    wav_blobs = []
    for s in manifest:
        with open(os.path.join(SOUND_DIR, s["file"]), "rb") as f:
            wav_blobs.append(f.read())

    out = bytearray()
    out += b"ElecbyteSnd\x00"
    out += u16(1)            # version (u16)
    out += u16(0)            # compat version (u16)
    out += u32(n)            # number of sounds
    out += u32(first_sub)    # first subheader offset
    assert len(out) == header_size

    for i, s in enumerate(manifest):
        blob = wav_blobs[i]
        this_sub = len(out)                       # this subheader's offset
        nxt = this_sub + sub_size + len(blob) if i + 1 < n else 0
        out += u32(nxt) + u32(len(blob)) + i32(s["group"]) + i32(s["number"])
        assert len(out) == this_sub + sub_size
        out += blob

    os.makedirs(os.path.dirname(OUT_SND), exist_ok=True)
    with open(OUT_SND, "wb") as f:
        f.write(out)

    print(f"Wrote {OUT_SND}: {len(out)} bytes, {n} sounds")


if __name__ == "__main__":
    main()
