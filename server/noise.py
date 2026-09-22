"""Fast, temporally-coherent procedural fields (fBm) used by every scene.

Trick: per octave we pre-bake a few *phases* of a coarse random grid upsampled
with bicubic filtering. Per frame we blend between two phases (cyclically) and
crop a drifting window out of a padded canvas — this gives smooth "boiling +
flow" motion at a fraction of the cost of true 3-D noise.
"""
from __future__ import annotations

import numpy as np
from PIL import Image


def _upsample(grid: np.ndarray, w: int, h: int) -> np.ndarray:
    im = Image.fromarray((np.clip(grid, 0, 1) * 255).astype(np.uint8), mode="L")
    im = im.resize((w, h), Image.BICUBIC)
    return np.asarray(im, dtype=np.float32) / 255.0


class NoiseLib:
    """Bakes octave phases once, serves animated fBm crops per frame."""

    def __init__(self, seed: int, h: int, w: int, pad: int = 128):
        self.rng = np.random.default_rng(int(seed) & 0x7FFFFFFF)
        self.h, self.w, self.pad = h, w, pad

    # ------------------------------------------------------------ bake
    def bake_fbm(self, octaves: int = 4, base: int = 3, phases: int = 3,
                 persistence: float = 0.55, seed: int | None = None) -> "FbmField":
        rng = np.random.default_rng(seed if seed is not None else
                                    int(self.rng.integers(0, 2**31)))
        h, w, pad = self.h, self.w, self.pad
        layers: list[list[np.ndarray]] = []
        for o in range(octaves):
            gw = max(2, base * (2 ** o))
            gh = max(2, int(round(gw * h / w)))
            ph = [_upsample(rng.random((gh, gw), dtype=np.float32), w + pad, h + pad)
                  for _ in range(phases)]
            layers.append(ph)
        return FbmField(layers, persistence, h, w, pad)


class FbmField:
    def __init__(self, layers: list[list[np.ndarray]], persistence: float,
                 h: int, w: int, pad: int):
        self.layers, self.persistence = layers, persistence
        self.h, self.w, self.pad = h, w, pad
        self.phases = len(layers[0])

    def at(self, t: float, drift: tuple[float, float] = (0.0, 0.0),
           phase_rate: float = 0.5) -> np.ndarray:
        """Animated fBm; t in seconds; drift in *fine pixels* (can grow unbounded)."""
        h, w, pad = self.h, self.w, self.pad
        nph = self.phases
        pt = (t * phase_rate) % nph
        i0 = int(pt) % nph
        i1 = (i0 + 1) % nph
        fr = pt - int(pt)
        fr = fr * fr * (3 - 2 * fr)
        margin = pad // 2 - 2
        dx0 = int(np.clip(drift[0], -margin, margin))
        dy0 = int(np.clip(drift[1], -margin, margin))
        out = np.zeros((h, w), dtype=np.float32)
        amp, tot, octaves = 1.0, 0.0, len(self.layers)
        for o in range(octaves):
            k = max(1, 1 << (octaves - 1 - o))       # fine octaves drift slower
            cx = pad // 2 + dx0 // k
            cy = pad // 2 + dy0 // k
            a = self.layers[o][i0]
            b = self.layers[o][i1]
            fa = a[cy:cy + h, cx:cx + w]
            fb = b[cy:cy + h, cx:cx + w]
            out += (fa * (1 - fr) + fb * fr) * amp
            tot += amp
            amp *= self.persistence
        return out / tot
