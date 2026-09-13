#!/usr/bin/env python3
"""
watch_drop.py - Watch for a dropped Saturn sprite (single render or sheet) and
process it automatically the moment it appears.

Watches two locations for any new image file:
  * /home/user/uploads/
  * /home/user/tfegfsd/chars/Saturn/source/

When a new image is found it runs the full pipeline:
    ingest_sprite.py --input <file> --preview   (splits sheet, builds SFF)
    build_snd.py
    validate.py

Usage:
    python3 tools/watch_drop.py
"""

import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WATCH_DIRS = [
    os.path.join("/home/user", "uploads"),
    os.path.join(ROOT, "chars", "Saturn", "source"),
]
EXTS = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")

seen = set()


def scan():
    found = []
    for d in WATCH_DIRS:
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.lower().endswith(EXTS):
                continue
            p = os.path.join(d, f)
            if p not in seen:
                found.append(p)
    return found


def process(path):
    print(f"[watch_drop] FOUND {path}", flush=True)
    try:
        subprocess.run([sys.executable, os.path.join(ROOT, "tools", "ingest_sprite.py"),
                        "--input", path, "--preview"], check=True)
        subprocess.run([sys.executable, os.path.join(ROOT, "tools", "validate.py")],
                       check=False)
        print(f"[watch_drop] DONE processing {path}", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"[watch_drop] ERROR {e}", flush=True)
    seen.add(path)


def main():
    print("[watch_drop] watching: " + ", ".join(WATCH_DIRS), flush=True)
    # mark anything already present as seen
    for d in WATCH_DIRS:
        if os.path.isdir(d):
            for f in os.listdir(d):
                if f.lower().endswith(EXTS):
                    seen.add(os.path.join(d, f))
    while True:
        for p in scan():
            process(p)
        time.sleep(3)


if __name__ == "__main__":
    main()
