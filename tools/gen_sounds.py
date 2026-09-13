#!/usr/bin/env python3
"""
gen_sounds.py - Synthesize 24 original 16-bit/22050 Hz mono WAV sound effects
and voice-ish placeholder sounds for the Saturn fan character.

Every sound is generated procedurally from scratch (sine/noise synthesis).
No copyrighted audio is used or embedded.

Usage:
    python3 tools/gen_sounds.py

Outputs:
    chars/Saturn/_build/sounds/<group>_<number>.wav
    chars/Saturn/_build/sounds/manifest.json
"""

import json
import math
import os
import random
import struct
import wave

import numpy as np

SR = 22050
OUT_DIR = os.path.join("chars", "Saturn", "_build", "sounds")


def wav_bytes(samples):
    """float samples in [-1,1] -> 16-bit mono WAV file bytes."""
    import io
    samples = np.clip(samples, -1.0, 1.0)
    pcm = (samples * 32767).astype("<i2")
    bio = io.BytesIO()
    with wave.open(bio, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return bio.getvalue()


def env_ar(n, attack, release):
    """Attack/release envelope (linear) over n samples."""
    a = int(n * attack)
    r = int(n * release)
    e = np.ones(n, dtype=np.float64)
    if a > 0:
        e[:a] = np.linspace(0, 1, a)
    if r > 0:
        e[-r:] *= np.linspace(1, 0, r)
    return e


def tone(freq, dur, attack=0.02, release=0.2, sweep=None):
    n = int(SR * dur)
    t = np.arange(n) / SR
    if sweep is not None:
        f = np.linspace(freq, sweep, n)
        ph = 2 * np.pi * np.cumsum(f) / SR
        s = np.sin(ph)
    else:
        s = np.sin(2 * np.pi * freq * t)
    return s * env_ar(n, attack, release)


def noise(dur, attack=0.01, release=0.3):
    n = int(SR * dur)
    rng = np.random.default_rng(n)
    s = rng.uniform(-1, 1, n)
    return s * env_ar(n, attack, release)


def lowpass(x, strength=0.6):
    """One-pole lowpass (moving average)."""
    a = strength
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc = acc * a + (1 - a) * x[i]
        y[i] = acc
    return y


def highpass(x, strength=0.6):
    return x - lowpass(x, strength)


def normalize(x, peak=0.95):
    m = np.max(np.abs(x)) or 1.0
    return x / m * peak


def mix(*arrs):
    """Sum same- or different-length 1-D arrays, zero-padding to the longest."""
    n = max(len(a) for a in arrs)
    out = np.zeros(n, dtype=np.float64)
    for a in arrs:
        out[:len(a)] += a
    return out


# --------------------------------------------------------------------------
# Individual sound recipes
# --------------------------------------------------------------------------
def s_hit_light():
    n = int(SR * 0.16)
    thump = tone(180, 0.16, 0.002, 0.9, sweep=80)
    tap = noise(0.08, 0.001, 0.9) * 0.5
    return normalize(mix(thump, tap))


def s_hit_medium():
    n = int(SR * 0.22)
    thump = tone(150, 0.22, 0.002, 0.9, sweep=70)
    body = lowpass(noise(0.16, 0.001, 0.8), 0.8) * 0.6
    return normalize(mix(thump, body))


def s_hit_heavy():
    n = int(SR * 0.3)
    thump = tone(120, 0.3, 0.002, 0.95, sweep=55)
    boom = lowpass(noise(0.25, 0.001, 0.85), 0.9) * 0.7
    return normalize(mix(thump, boom))


def s_guard():
    n = int(SR * 0.14)
    clank = tone(900, 0.1, 0.002, 0.7, sweep=500) * 0.5
    tick = highpass(noise(0.08, 0.001, 0.8), 0.7) * 0.6
    return normalize(mix(clank, tick))


def s_slash():
    n = int(SR * 0.22)
    hiss = highpass(noise(0.22, 0.01, 0.7), 0.85)
    sw = tone(500, 0.2, 0.005, 0.8, sweep=2000) * 0.3
    return normalize(mix(hiss, sw))


def s_claw():
    n = int(SR * 0.28)
    rip = highpass(noise(0.28, 0.005, 0.75), 0.9)
    low = tone(220, 0.25, 0.005, 0.85, sweep=90) * 0.5
    return normalize(mix(rip, low))


def s_fire():
    n = int(SR * 0.2)
    f = tone(1400, 0.2, 0.005, 0.9, sweep=200)
    whoosh = lowpass(noise(0.2, 0.01, 0.8), 0.6) * 0.4
    return normalize(mix(f, whoosh))


def s_proj_hit():
    n = int(SR * 0.18)
    fizz = highpass(noise(0.16, 0.001, 0.85), 0.8) * 0.7
    pop = tone(300, 0.1, 0.002, 0.8, sweep=90) * 0.6
    return normalize(mix(fizz, pop))


def s_explosion():
    n = int(SR * 0.6)
    boom = lowpass(noise(0.6, 0.002, 0.9), 0.95)
    sub = tone(60, 0.6, 0.002, 0.9, sweep=35) * 1.2
    return normalize(mix(boom, sub))


def s_shockwave():
    n = int(SR * 0.4)
    sweep = tone(60, 0.4, 0.005, 0.85, sweep=180) * 0.9
    rumble = lowpass(noise(0.4, 0.005, 0.85), 0.9) * 0.5
    return normalize(mix(sweep, rumble))


def s_transform():
    n = int(SR * 1.1)
    t = np.arange(n) / SR
    f = np.linspace(80, 700, n)
    ph = 2 * np.pi * np.cumsum(f) / SR
    rise = np.sin(ph) * env_ar(n, 0.2, 0.3)
    shimmer = tone(2200, 1.0, 0.3, 0.4, sweep=2600) * 0.25
    return normalize(mix(rise, shimmer))


def s_heal():
    n = int(SR * 0.9)
    t = np.arange(n) / SR
    chime = (np.sin(2 * np.pi * 523 * t) + np.sin(2 * np.pi * 659 * t) * 0.7
             + np.sin(2 * np.pi * 784 * t) * 0.5) * env_ar(n, 0.1, 0.6)
    return normalize(chime)


def s_hiss():
    n = int(SR * 0.5)
    return normalize(highpass(noise(0.5, 0.05, 0.7), 0.9))


def s_rumble():
    n = int(SR * 0.8)
    low = tone(45, 0.8, 0.05, 0.6, sweep=50) * 1.3
    return normalize(mix(low, lowpass(noise(0.8, 0.05, 0.7), 0.95) * 0.4))


def s_eerie():
    n = int(SR * 0.7)
    t = np.arange(n) / SR
    vib = 900 + 60 * np.sin(2 * np.pi * 6 * t)
    s = np.sin(2 * np.pi * vib * t)
    return normalize(s * env_ar(n, 0.1, 0.5))


def s_land():
    n = int(SR * 0.18)
    return normalize(mix(tone(90, 0.18, 0.002, 0.9, sweep=50),
                         lowpass(noise(0.12, 0.001, 0.8), 0.9) * 0.5))


def s_whoosh():
    n = int(SR * 0.3)
    return normalize(lowpass(noise(0.3, 0.05, 0.7), 0.7))


def s_sparkle():
    n = int(SR * 0.25)
    t = np.arange(n) / SR
    s = np.sin(2 * np.pi * 1800 * t) * np.sin(2 * np.pi * 30 * t)
    return normalize(s * env_ar(n, 0.01, 0.9))


def s_roar1():
    n = int(SR * 0.9)
    t = np.arange(n) / SR
    f = 110 + 40 * np.sin(2 * np.pi * 3 * t)
    saw = 2 * ((t * f) % 1.0) - 1.0
    growl = lowpass(saw, 0.8)
    return normalize(growl * env_ar(n, 0.1, 0.4))


def s_roar2():
    n = int(SR * 1.1)
    t = np.arange(n) / SR
    f = 80 + 30 * np.sin(2 * np.pi * 2.5 * t)
    saw = 2 * ((t * f) % 1.0) - 1.0
    growl = lowpass(saw, 0.85)
    sub = tone(55, 1.1, 0.1, 0.4) * 0.8
    return normalize(mix(growl * env_ar(n, 0.15, 0.5), sub))


def s_grunt1():
    n = int(SR * 0.25)
    t = np.arange(n) / SR
    saw = 2 * ((t * 150) % 1.0) - 1.0
    return normalize(lowpass(saw, 0.7) * env_ar(n, 0.02, 0.7))


def s_grunt2():
    n = int(SR * 0.3)
    t = np.arange(n) / SR
    saw = 2 * ((t * 120) % 1.0) - 1.0
    return normalize(lowpass(saw, 0.75) * env_ar(n, 0.02, 0.7))


def s_laugh():
    n = int(SR * 1.4)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for i in range(6):
        st = int(SR * (0.1 + i * 0.22))
        seg = min(int(SR * 0.18), n - st)
        if seg <= 0:
            break
        seg_t = np.arange(seg) / SR
        saw = 2 * ((seg_t * (160 - i * 12)) % 1.0) - 1.0
        out[st:st + seg] += saw * env_ar(seg, 0.02, 0.9)
    return normalize(lowpass(out, 0.7))


def s_shout():
    n = int(SR * 0.7)
    t = np.arange(n) / SR
    f = 200 + 80 * np.sin(2 * np.pi * 4 * t)
    saw = 2 * ((t * f) % 1.0) - 1.0
    return normalize(lowpass(saw, 0.6) * env_ar(n, 0.05, 0.4))


# --------------------------------------------------------------------------
# Registry
# --------------------------------------------------------------------------
SOUNDS = [
    (5, 0, "hit_light", s_hit_light),
    (5, 1, "hit_medium", s_hit_medium),
    (5, 2, "hit_heavy", s_hit_heavy),
    (5, 3, "guard", s_guard),
    (5, 4, "slash", s_slash),
    (5, 5, "claw", s_claw),
    (5, 6, "fire", s_fire),
    (5, 7, "proj_hit", s_proj_hit),
    (5, 8, "explosion", s_explosion),
    (5, 9, "shockwave", s_shockwave),
    (5, 10, "transform", s_transform),
    (5, 11, "heal", s_heal),
    (5, 12, "hiss", s_hiss),
    (5, 13, "rumble", s_rumble),
    (5, 14, "eerie", s_eerie),
    (5, 15, "land", s_land),
    (5, 16, "whoosh", s_whoosh),
    (5, 17, "sparkle", s_sparkle),
    (10, 0, "roar1", s_roar1),
    (10, 1, "roar2", s_roar2),
    (10, 2, "grunt1", s_grunt1),
    (10, 3, "grunt2", s_grunt2),
    (10, 4, "laugh", s_laugh),
    (10, 5, "shout", s_shout),
]


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    manifest = []
    for g, n, name, fn in SOUNDS:
        data = wav_bytes(fn())
        path = os.path.join(OUT_DIR, f"{g}_{n}.wav")
        with open(path, "wb") as f:
            f.write(data)
        manifest.append({"group": g, "number": n, "name": name,
                         "file": f"{g}_{n}.wav", "bytes": len(data)})
    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print(f"Wrote {len(manifest)} sounds to {OUT_DIR}")


if __name__ == "__main__":
    main()
