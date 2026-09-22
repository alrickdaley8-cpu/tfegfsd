"""Virtual camera moves — the Higgsfield-style "motion control" layer.

Every move outputs (scale, dx, dy, rot) for a normalized time tn∈[0,1] in
scene-normalized coordinates (x∈[-aspect,aspect], y∈[-1,1]). Field scenes use
`warp` to remap their sample coordinates; particle scenes use `project`.
"""
from __future__ import annotations

import numpy as np

MOTIONS: dict[str, dict] = {
    "auto":       {"label": "Auto (director picks)", "desc": "Smart default for the world"},
    "static":     {"label": "Tripod",                "desc": "Locked-off frame"},
    "dolly_in":   {"label": "Dolly In",              "desc": "Slow push toward the subject"},
    "dolly_out":  {"label": "Dolly Out",             "desc": "Slow pull-back reveal"},
    "pan":        {"label": "Lateral Pan",           "desc": "Sideways tracking move"},
    "orbit":      {"label": "Orbit",                 "desc": "Arc around the subject"},
    "crash_zoom": {"label": "Crash Zoom",            "desc": "Snap-whip zoom punches"},
    "whip_pan":   {"label": "Whip Pan",              "desc": "Fast blurred sweep"},
    "fpv":        {"label": "FPV Drone",             "desc": "Floaty first-person drift"},
    "handheld":   {"label": "Handheld",              "desc": "Organic documentary shake"},
}


def _smooth_noise(rng: np.random.Generator, n: int, k: int = 15) -> np.ndarray:
    x = rng.standard_normal(n).astype(np.float32)
    kern = np.hanning(k).astype(np.float32)
    kern /= kern.sum()
    y = np.convolve(x, kern, mode="same")
    y /= (np.abs(y).max() + 1e-6)
    return y


def smoothstep(x: np.ndarray | float) -> np.ndarray | float:
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


class Camera:
    def __init__(self, motion: str, h: int, w: int, seed: int):
        self.motion = motion
        self.aspect = w / h
        rng = np.random.default_rng((int(seed) ^ 0x5EEDCA) & 0x7FFFFFFF)
        self.n1 = _smooth_noise(rng, 240, 21)
        self.n2 = _smooth_noise(rng, 240, 17)
        self.n3 = _smooth_noise(rng, 240, 13)
        self.dir = 1.0 if rng.random() > 0.5 else -1.0
        self.double_crash = rng.random() > 0.5

    # ----------------------------------------------------- per-shot params
    def _at(self, arr: np.ndarray, tn: float) -> float:
        i = min(len(arr) - 1, max(0, int(tn * len(arr))))
        return float(arr[i])

    def params(self, tn: float) -> tuple[float, float, float, float]:
        """-> (scale, dx, dy, rot_deg). dx/dy in normalized units."""
        m = self.motion
        a = self.aspect
        s = smoothstep(tn)
        if m == "dolly_in":
            return 1.0 - 0.30 * s, 0.0, 0.0, 0.0
        if m == "dolly_out":
            return 0.74 + 0.26 * s, 0.0, 0.0, 0.0
        if m == "pan":
            return 0.92, (s - 0.5) * 0.12 * a * self.dir, 0.0, 0.0
        if m == "orbit":
            return 0.90 - 0.05 * np.sin(tn * np.pi), 0.05 * a * np.sin(tn * np.pi) * self.dir, \
                0.02 * np.sin(tn * 2 * np.pi), 7.0 * (s - 0.5) * 2 * self.dir
        if m == "crash_zoom":
            def punch(c: float) -> float:
                # quick in (10% of clip), hold, spring back
                if tn < c or tn > c + 0.22:
                    return 0.0
                u = (tn - c) / 0.22
                if u < 0.35:
                    return smoothstep(u / 0.35)
                return 1.0 - smoothstep((u - 0.35) / 0.65)
            z = 0.42 * punch(0.32)
            if self.double_crash:
                z = max(z, 0.38 * punch(0.68))
            return 1.0 - z, 0.02 * a * punch(0.32) * self.dir, 0.0, 0.0
        if m == "whip_pan":
            u = np.clip((tn - 0.18) / 0.34, 0, 1)
            sweep = smoothstep(u)
            return 0.95, (sweep - 0.5) * 0.55 * a * self.dir, 0.0, 4.0 * np.sin(u * np.pi) * self.dir
        if m == "fpv":
            i1, i2, i3 = self._at(self.n1, tn), self._at(self.n2, tn), self._at(self.n3, tn)
            return (0.86 + 0.10 * np.sin(tn * np.pi * 0.9) + 0.03 * i1,
                    0.03 * a * i2, 0.035 * i3 - 0.05 * np.sin(tn * np.pi),
                    4.0 * i1)
        if m == "handheld":
            i1, i2 = self._at(self.n1, tn), self._at(self.n2, tn)
            hi = np.sin(tn * 43.0) * 0.15 + i1 * 0.85
            hj = np.cos(tn * 37.0) * 0.15 + i2 * 0.85
            return 0.97 + 0.01 * self._at(self.n3, tn), 0.012 * a * hi, 0.012 * hj, 0.8 * i1
        return 1.0, 0.0, 0.0, 0.0  # static

    # ----------------------------------------------------------- mapping
    def warp(self, xx: np.ndarray, yy: np.ndarray, tn: float) -> tuple[np.ndarray, np.ndarray]:
        """Map *output* normalized coords -> *source* coords to sample from."""
        scale, dx, dy, rot = self.params(tn)
        th = np.deg2rad(rot)
        c, s_ = np.cos(th), np.sin(th)
        # inverse-rotate then scale then translate (sampling the source)
        xs = (xx * c + yy * s_) * scale + dx
        ys = (-xx * s_ + yy * c) * scale + dy
        return xs.astype(np.float32), ys.astype(np.float32)

    def project(self, xs: np.ndarray, ys: np.ndarray, tn: float) -> tuple[np.ndarray, np.ndarray]:
        """Forward-map *scene* points -> *output* pixel coords (h, w needed by caller)."""
        scale, dx, dy, rot = self.params(tn)
        th = np.deg2rad(-rot)
        c, s_ = np.cos(th), np.sin(th)
        xs = (xs - dx) / scale
        ys = (ys - dy) / scale
        xo = xs * c + ys * s_
        yo = -xs * s_ + ys * c
        return xo, yo
