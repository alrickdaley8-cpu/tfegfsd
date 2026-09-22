"""The twelve "worlds" — procedural scene renderers.

Each scene produces float32 RGB frames in [0,1] shaped (h, w, 3). Scenes get a
shared `Ctx` with baked noise fields, a virtual camera, palette accents and an
energy knob, and keep any particle state on the instance.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageDraw

from .noise import NoiseLib, FbmField
from .camera import Camera

# ---------------------------------------------------------------- utilities

def f32(x) -> np.ndarray:
    return np.asarray(x, dtype=np.float32)


def clip01(x: np.ndarray) -> np.ndarray:
    return np.clip(x, 0.0, 1.0)


def smoothstep(a, b, x: np.ndarray) -> np.ndarray:
    """GLSL-style smoothstep; elementwise, tolerates a > b (reversed ramp)."""
    a_, b_ = np.broadcast_arrays(np.asarray(a, np.float32), np.asarray(b, np.float32))
    lo = np.minimum(a_, b_)
    hi = np.maximum(a_, b_)
    t = clip01((x - lo) / np.maximum(hi - lo, 1e-6))
    s = t * t * (3 - 2 * t)
    return np.where(a_ > b_, 1.0 - s, s).astype(np.float32)


def make_ramp(stops: list[tuple[float, float, float]]):
    """Palette ramp: maps (H,W) values in [0,1] to (H,W,3) RGB."""
    arr = f32(stops)
    n = len(arr) - 1

    def f(v: np.ndarray) -> np.ndarray:
        pos = clip01(v) * n
        i0 = np.minimum(pos.astype(np.int32), n - 1)
        fr = (pos - i0)[..., None]
        return arr[i0] * (1 - fr) + arr[i0 + 1] * fr
    return f


def radial(xx, yy, cx, cy, r2):
    d2 = (xx - cx) ** 2 + (yy - cy) ** 2
    return np.exp(-d2 / r2)


def splat_points(img, xs, ys, cols, h, w, gain=1.0):
    xi = np.round(xs).astype(np.int32)
    yi = np.round(ys).astype(np.int32)
    m = (xi >= 0) & (xi < w) & (yi >= 0) & (yi < h)
    if not m.any():
        return
    for c in range(3):
        np.add.at(img[..., c], (yi[m], xi[m]), cols[m, c] * gain)


_STAMPS: dict[int, np.ndarray] = {}


def _stamp(r: int) -> np.ndarray:
    if r not in _STAMPS:
        y, x = np.mgrid[-r:r + 1, -r:r + 1].astype(np.float32)
        s = np.exp(-(x ** 2 + y ** 2) / (2 * (r / 2.2) ** 2))
        _STAMPS[r] = s.astype(np.float32)
    return _STAMPS[r]


def splat_blobs(img, xs, ys, cols, radii, h, w):
    for x, y, col, r in zip(xs, ys, cols, radii):
        r = int(r)
        xi, yi = int(round(x)), int(round(y))
        st = _stamp(r)
        x0, x1 = xi - r, xi + r + 1
        y0, y1 = yi - r, yi + r + 1
        sx0, sy0 = max(0, -x0), max(0, -y0)
        if x1 <= 0 or y1 <= 0 or x0 >= w or y0 >= h:
            continue
        cx0, cy0 = max(0, x0), max(0, y0)
        cx1, cy1 = min(w, x1), min(h, y1)
        sub = st[sy0:sy0 + (cy1 - cy0), sx0:sx0 + (cx1 - cx0)]
        img[cy0:cy1, cx0:cx1] += sub[..., None] * col[None, None, :]


# ------------------------------------------------------------------- context

@dataclass
class Ctx:
    h: int
    w: int
    seed: int
    camera: Camera
    accents: list[tuple[float, float, float]] = field(default_factory=list)
    energy: float = 0.5
    night: float = 0.5

    def __post_init__(self):
        self.rng = np.random.default_rng(int(self.seed) & 0x7FFFFFFF)
        self.lib = NoiseLib(self.seed, self.h, self.w)
        self.aspect = self.w / self.h
        a = self.aspect
        yy, xx = np.mgrid[0:self.h, 0:self.w].astype(np.float32)
        self.xx = (xx / (self.w - 1) * 2 - 1) * a
        self.yy = yy / (self.h - 1) * 2 - 1
        self.pix_y = yy
        self.pix_x = xx
        self.fields: dict[str, FbmField] = {}

    def field(self, name: str, **kw) -> FbmField:
        if name not in self.fields:
            self.fields[name] = self.lib.bake_fbm(**kw)
        return self.fields[name]

    def view(self, tn: float, cam_k: float) -> tuple[np.ndarray, np.ndarray]:
        """Sample coordinates mixed between identity and the virtual camera."""
        sx, sy = self.camera.warp(self.xx, self.yy, tn)
        if cam_k < 1.0:
            sx = self.xx * (1 - cam_k) + sx * cam_k
            sy = self.yy * (1 - cam_k) + sy * cam_k
        return sx, sy

    def project_pts(self, xs, ys, tn, parallax: float = 1.0):
        xo, yo = self.camera.project(xs, ys, tn)
        # blend toward raw for parallax layers
        xo = xs * (1 - parallax) + xo * parallax
        yo = ys * (1 - parallax) + yo * parallax
        px = (xo / self.aspect * 0.5 + 0.5) * (self.w - 1)
        py = (yo * 0.5 + 0.5) * (self.h - 1)
        return px, py

    def accent(self, i: int, fallback: tuple[float, float, float]) -> np.ndarray:
        if self.accents:
            return f32(self.accents[i % len(self.accents)])
        return f32(fallback)


class Scene:
    id = "base"
    label = "Base"
    cam_k = 0.6          # how strongly the virtual camera affects the scene
    default_motion = "dolly_in"

    def __init__(self, ctx: Ctx):
        self.ctx = ctx

    def render(self, i: int, tn: float, t: float) -> np.ndarray:  # pragma: no cover
        raise NotImplementedError


# ------------------------------------------------------------------- stars

class StarMixin:
    def make_stars(self, n=140, layers=3):
        ctx = self.ctx
        rng = ctx.rng
        st = []
        for l in range(layers):
            m = n // layers
            st.append(dict(
                xs=rng.uniform(-1.35, 1.35, m) * ctx.aspect,
                ys=rng.uniform(-1.15, 1.15, m),
                amp=rng.uniform(0.25, 1.0, m).astype(np.float32),
                ph=rng.uniform(0, 6.28, m).astype(np.float32),
                sp=rng.uniform(0.6, 3.0, m).astype(np.float32),
                pf=0.55 + 0.35 * l / max(1, layers - 1),
            ))
        return st

    def draw_stars(self, img, stars, tn, t, tint=(1.0, 1.0, 1.0), gain=1.0):
        ctx = self.ctx
        for l, st in enumerate(stars):
            tw = st["amp"] * (0.55 + 0.45 * np.sin(t * st["sp"] + st["ph"]))
            cols = (tw[:, None] * f32(tint)[None, :]).astype(np.float32)
            px, py = ctx.project_pts(st["xs"], st["ys"], tn, parallax=st["pf"])
            splat_points(img, px, py, cols, ctx.h, ctx.w, gain=gain)


# ---------------------------------------------------------------- 1. nebula

class NebulaScene(Scene, StarMixin):
    id, label = "nebula", "Deep Space Nebula"
    cam_k, default_motion = 0.55, "dolly_in"

    def __init__(self, ctx):
        super().__init__(ctx)
        self.stars = self.make_stars(220, 3)
        c1 = ctx.accent(0, (0.30, 0.10, 0.62))
        c2 = ctx.accent(1, (0.85, 0.25, 0.55))
        c3 = ctx.accent(2, (0.15, 0.45, 0.85))
        self.rampA = make_ramp([(0.006, 0.003, 0.022), tuple(c1 * 0.8), tuple(c3)])
        self.rampB = make_ramp([(0.01, 0.005, 0.02), tuple(c2 * 0.6), (1.0, 0.85, 0.9)])
        self.cx, self.cy = float(ctx.rng.uniform(-0.3, 0.3)), float(ctx.rng.uniform(-0.2, 0.2))
        self.core_c = ctx.accent(2, (0.4, 0.3, 0.6))

    def render(self, i, tn, t):
        ctx = self.ctx
        sx, sy = ctx.view(tn, self.cam_k)
        ox = ((sx / ctx.aspect) * 0.5) * 40.0
        oy = (sy * 0.5) * 40.0
        f1 = ctx.field("neb1", octaves=5, base=2, phases=3, persistence=0.62, seed=11)
        f2 = ctx.field("neb2", octaves=4, base=3, phases=3, persistence=0.55, seed=23)
        n1 = np.roll(f1.at(t, drift=(t * 9, t * 4), phase_rate=0.22), (int(oy[0, 0]), int(ox[0, 0])), (0, 1))
        n2 = np.roll(f2.at(t * 1.2 + 7, drift=(-t * 14, t * 7), phase_rate=0.3), (int(-oy[0, 0]), int(-ox[0, 0])), (0, 1))
        gasA = clip01(n1 * 0.9 + n2 * 0.38 - 0.33) ** 2.0
        gasB = clip01(f1.at(t + 41, drift=(t * 18, -t * 10), phase_rate=0.26) * 0.65 + n2 * 0.45 - 0.34) ** 2.3
        img = self.rampA(gasA) + self.rampB(gasB) * (gasB[..., None] * 1.2)
        core = radial(ctx.xx, ctx.yy, self.cx, self.cy, 0.10)
        img += core[..., None] * self.core_c * 0.3
        img += (radial(ctx.xx, ctx.yy, self.cx, self.cy, 0.006) ** 1.5)[..., None] * f32((1.0, 0.95, 0.9)) * 0.7
        self.draw_stars(img, self.stars, tn, t, gain=1.3)
        return clip01(img)


# ---------------------------------------------------------------- 2. liquid

class LiquidScene(Scene):
    id, label = "liquid", "Liquid Chrome"
    cam_k, default_motion = 0.5, "orbit"

    def __init__(self, ctx):
        super().__init__(ctx)
        a1 = np.asarray(ctx.accent(0, (0.55, 0.35, 0.75)), dtype=np.float32)
        self.palA = f32((0.45, 0.42, 0.52)) * 0.6 + a1 * 0.4
        self.palB = f32((0.5, 0.5, 0.5))
        self.palC = f32((1.0, 1.0, 1.0))
        d = ctx.accent(1, (0.0, 0.33, 0.67))
        self.palD = f32(d)

    def render(self, i, tn, t):
        ctx = self.ctx
        sx, sy = ctx.view(tn, self.cam_k)
        f = ctx.field("liq", octaves=4, base=2, phases=3, persistence=0.6, seed=5)
        n1 = f.at(t, drift=(t * 8, t * 5), phase_rate=0.2)
        n2 = f.at(t * 0.8 + 19, drift=(-t * 11, t * 9), phase_rate=0.27)
        wob = ((sx - ctx.xx) ** 2 + (sy - ctx.yy) ** 2) * 2.0
        v = n1 * 0.6 + n2 * 0.45 + wob * 0.4 + (sx * 0.15 + sy * 0.1) + t * 0.03
        col = self.palA + self.palB * np.cos(2 * np.pi * (self.palC * v[..., None] + self.palD))
        col = clip01((col - 0.5) * 1.45 + 0.46)          # chrome contrast
        sheen = clip01(np.cos(2 * np.pi * (v * 2.0 + 0.15)) ** 10)
        col += sheen[..., None] * 0.5
        return clip01(col)


# ---------------------------------------------------------------- 3. neon

class NeonGridScene(Scene):
    id, label = "neon", "Synthwave Grid"
    cam_k, default_motion = 0.7, "dolly_in"

    def __init__(self, ctx):
        super().__init__(ctx)
        self.stars = StarMixin.make_stars(self, 160, 2)
        c1 = ctx.accent(0, (1.0, 0.25, 0.75))
        c2 = ctx.accent(1, (0.25, 0.8, 1.0))
        self.mag, self.cyn = f32(c1), f32(c2)
        rng = ctx.rng
        self.ridge1 = rng.uniform(0, 6.28, 5), rng.uniform(0.02, 0.10, 5)
        self.ridge2 = rng.uniform(0, 6.28, 5), rng.uniform(0.03, 0.14, 5)

    def _ridge(self, x, pars, base):
        ph, am = pars
        y = np.full_like(x, base)
        for k in range(5):
            y -= am[k] * np.abs(np.sin(x * (k + 1) * 0.9 + ph[k]))
        return y

    def render(self, i, tn, t):
        ctx = self.ctx
        scale, dxc, dyc, _ = ctx.camera.params(tn)
        hor = -0.08 + dyc * 0.4          # horizon line in norm coords
        yv = ctx.yy
        sky = clip01((hor - yv) * 1.1)
        img = np.zeros((ctx.h, ctx.w, 3), dtype=np.float32)
        # sky gradient deep purple -> hot pink near horizon
        g = clip01(1 - sky)
        img += (f32((0.03, 0.0, 0.10)) * (1 - g[..., None]) + f32((0.55, 0.08, 0.45)) * (g[..., None] * 0.8)) * (sky[..., None] > 0.001)
        # sun with scanlines
        sx, sy0 = 0.0 - dxc * 0.5, hor - 0.42
        d2 = (ctx.xx - sx) ** 2 + (yv - sy0) ** 2
        sun = clip01(1 - d2 / 0.055)
        scan = 0.5 + 0.5 * np.sin(yv * 260 - t * 2)
        cut = smoothstep(sy0 - 0.05, sy0 + 0.22, yv)
        sun *= 1 - (cut * (scan < 0.45)).astype(np.float32)
        sung = np.exp(-d2 / 0.30)
        img += sun[..., None] * f32((1.0, 0.75, 0.35)) + sung[..., None] * self.mag * 0.35
        StarMixin.draw_stars(self, img, self.stars, tn, t, gain=0.8)
        # mountains (two silhouettes) — ridge lines are 1-D over x
        xg = ctx.xx[0] + dxc * 0.3
        r1 = self._ridge(xg, self.ridge1, hor - 0.02)[None, :]
        r2 = self._ridge(xg * 1.15 + 3.7, self.ridge2, hor - 0.015)[None, :]
        m1 = (yv > r1) & (yv < hor + 0.02)
        m2 = (yv > r2) & (yv < hor + 0.02)
        img[m2] = f32((0.05, 0.01, 0.10))
        img[m1] = f32((0.02, 0.0, 0.05))
        # floor grid (perspective)
        floor = yv >= hor
        z = np.maximum(yv - hor, 1e-3)
        persp = 0.10 / z
        u = (ctx.xx - dxc) * persp
        w = persp + t * (0.55 + ctx.energy)
        lu = np.abs(u * 3.0 % 1.0 - 0.5)
        lw = np.abs(w * 3.2 % 1.0 - 0.5)
        line = clip01(1 - np.minimum(lu / 0.022, lw / 0.032))
        fade = clip01(z * 3.2) * clip01(2.2 - z * 2.0)
        grid = line * fade
        glowR = grid * (0.5 + 0.5 * np.sin(ctx.xx * 2 + 1.2))
        img += grid[..., None] * (self.cyn * (1 - glowR[..., None]) + self.mag * glowR[..., None]) * 1.1
        img += (np.exp(-z * 7)[..., None]) * self.mag * 0.5 * fade[..., None]
        img[floor & (grid <= 0.001)] *= 0.25   # dark floor
        return clip01(img)


# ---------------------------------------------------------------- 4. ember

class EmberScene(Scene):
    id, label = "fire", "Fire & Embers"
    cam_k, default_motion = 0.3, "dolly_in"

    def __init__(self, ctx):
        super().__init__(ctx)
        self.ramp = make_ramp([(0.0, 0.0, 0.0), (0.25, 0.02, 0.0), (0.75, 0.16, 0.02),
                               (1.0, 0.65, 0.08), (1.0, 0.95, 0.55)])
        rng = ctx.rng
        self.n_em = int(90 * (0.6 + ctx.energy))
        self.ex = rng.uniform(-ctx.aspect * 1.2, ctx.aspect * 1.2, self.n_em)
        self.ey = rng.uniform(-1, 1, self.n_em)
        self.ev = rng.uniform(0.25, 0.8, self.n_em)
        self.esw = rng.uniform(0.3, 1.4, self.n_em)
        self.eph = rng.uniform(0, 6.28, self.n_em)
        self.eborn = rng.uniform(0, 10, self.n_em)

    def render(self, i, tn, t):
        ctx = self.ctx
        f = ctx.field("fire", octaves=5, base=2, phases=3, persistence=0.6, seed=77)
        speed = 55 + 90 * ctx.energy
        n = f.at(t * (1 + ctx.energy), drift=(0, t * speed), phase_rate=0.9)
        body = 0.58 - (ctx.yy * 0.5 + 0.5) * 1.02      # vertical falloff
        flame = clip01(n * 1.3 + body - 0.33) ** 1.5
        img = self.ramp(flame)
        # embers
        life = 6.0
        age = (t + self.eborn) % life
        yy = -1.15 + (age / life) * 2.4 * self.ev
        sway = np.sin(t * self.esw + self.eph) * 0.06
        xx = self.ex + sway
        flick = 0.5 + 0.5 * np.sin(t * 7 + self.eph * 3)
        fade = clip01(1 - age / life)
        cols = (self.ramp(np.full(self.n_em, 0.9)).reshape(self.n_em, 3)
                * (flick * fade)[:, None]).astype(np.float32)
        px = (xx / ctx.aspect * 0.5 + 0.5) * (ctx.w - 1)
        py = (yy * 0.5 + 0.5) * (ctx.h - 1)
        splat_points(img, px, py, cols * 1.6, ctx.h, ctx.w)
        return clip01(img)


# ---------------------------------------------------------------- 5. ocean

class OceanScene(Scene):
    id, label = "ocean", "Deep Ocean"
    cam_k, default_motion = 0.35, "pan"

    def __init__(self, ctx):
        super().__init__(ctx)
        rng = ctx.rng
        self.n = 60
        self.px0 = rng.uniform(0, ctx.w, self.n)
        self.py0 = rng.uniform(0, ctx.h, self.n)
        self.pv = rng.uniform(4, 14, self.n)
        self.deep = f32((0.005, 0.10, 0.16))
        self.top = f32((0.05, 0.45, 0.55)) * (0.5 + 0.8 * (1 - ctx.night))
        self.ray_ph = rng.uniform(0, 6.28, 5)
        self.ray_x = np.linspace(-0.8, 0.8, 5) * ctx.aspect

    def render(self, i, tn, t):
        ctx = self.ctx
        sx, sy = ctx.view(tn, self.cam_k)
        depth = clip01(ctx.yy * 0.5 + 0.5) ** 1.25
        img = self.top * (1 - depth[..., None]) * 0.9 + self.deep * depth[..., None]
        img = img.astype(np.float32).copy()
        # caustics (two interfering wave sets)
        k1 = sx * 3.0
        c1 = np.sin(k1 * 2.6 + t * 1.2) + np.sin((sx * 0.6 + sy) * 3.4 - t * 1.6)
        c2 = np.sin((sx - sy * 1.2) * 2.9 + t * 0.9) + np.sin(sy * 4.4 + t * 1.9)
        ca = (np.abs(np.sin(c1 * 0.45 + c2 * 0.35))) ** 10
        topmask = clip01(1.3 - depth * 1.6)
        img += (ca * topmask)[..., None] * f32((0.2, 0.85, 0.9)) * 0.5
        # god rays
        rays = np.zeros((ctx.h, ctx.w), dtype=np.float32)
        for k in range(5):
            cx = self.ray_x[k] + 0.10 * np.sin(t * 0.4 + self.ray_ph[k])
            rays += np.exp(-((ctx.xx - cx + sy * 0.3) / 0.065) ** 2) * 0.30
        img += (rays * topmask)[..., None] * f32((0.45, 0.8, 0.75))
        # surface shimmer
        surf = clip01(1 - np.abs(ctx.yy + 0.98) * 22)
        img += (surf * (0.5 + 0.3 * np.sin(t * 2 + ctx.xx * 6)))[..., None] * f32((0.7, 0.95, 1.0)) * 0.6
        # marine snow
        py = (self.py0 + t * self.pv) % ctx.h
        px = self.px0 + 6 * np.sin(t * 0.8 + self.py0 * 0.05)
        cols = np.full((self.n, 3), 0.55, dtype=np.float32) * f32((0.8, 0.95, 1.0))
        splat_points(img, px, py, cols, ctx.h, ctx.w, gain=0.5)
        return clip01(img)


# ---------------------------------------------------------------- 6. ink

class InkScene(Scene):
    id, label = "ink", "Ink Bloom"
    cam_k, default_motion = 0.4, "dolly_out"

    def __init__(self, ctx):
        super().__init__(ctx)
        rng = ctx.rng
        k = 3
        self.bx = rng.uniform(-ctx.aspect * 0.55, ctx.aspect * 0.55, k)
        self.by = rng.uniform(-0.45, 0.45, k)
        self.boff = rng.uniform(0, 0.35, k)
        self.paper = f32((0.93, 0.91, 0.87))
        self.inkc = ctx.accent(0, (0.08, 0.09, 0.14)) * 0.28   # ink is always near-black
        self.edgec = ctx.accent(1, (0.35, 0.2, 0.6))

    def render(self, i, tn, t):
        ctx = self.ctx
        sx, sy = ctx.view(tn, self.cam_k)
        f = ctx.field("ink", octaves=4, base=3, phases=3, persistence=0.6, seed=91)
        n = f.at(t * 0.7, drift=(t * 5, t * 3), phase_rate=0.18)
        n2 = f.at(t * 0.5 + 33, drift=(-t * 7, t * 4), phase_rate=0.22)
        img = np.empty((ctx.h, ctx.w, 3), dtype=np.float32)
        img[:] = self.paper * (0.95 + 0.08 * n2[..., None])
        for k in range(len(self.bx)):
            grow = smoothstep(0.0, 1.0, clip01((tn - self.boff[k]) / 0.6))
            if grow <= 0.001:
                continue
            r = 0.05 + 0.52 * grow
            d = np.sqrt((sx - self.bx[k]) ** 2 + (sy - self.by[k]) ** 2)
            edge = d - (n - 0.5) * 0.26 * grow
            inside = smoothstep(r, r - 0.12, edge)
            ring = clip01(1 - np.abs(edge - r) / 0.045) * 0.9
            pigment = clip01(inside * (0.55 + 0.6 * n))
            ink_rgb = self.inkc + self.edgec * 0.15
            img = img * (1 - pigment[..., None] * 0.88) + ink_rgb[None, None, :] * (pigment[..., None] * 0.88)
            img -= ring[..., None] * (1 - self.edgec) * 0.30
        return clip01(img)


# ---------------------------------------------------------------- 7. aurora

class AuroraScene(Scene, StarMixin):
    id, label = "aurora", "Aurora Ridge"
    cam_k, default_motion = 0.45, "pan"

    def __init__(self, ctx):
        super().__init__(ctx)
        self.stars = self.make_stars(180, 2)
        rng = ctx.rng
        self.curt = [(rng.uniform(-0.55, 0.1), rng.uniform(0.10, 0.2), rng.uniform(0.6, 1.4),
                      rng.uniform(0, 6.28)) for _ in range(3)]
        self.ac = f32(ctx.accent(0, (0.2, 1.0, 0.55)))
        self.ac2 = f32(ctx.accent(1, (0.5, 0.3, 1.0)))
        self.rp1 = rng.uniform(0, 6.28, 4), rng.uniform(0.03, 0.09, 4)
        self.rp2 = rng.uniform(0, 6.28, 4), rng.uniform(0.05, 0.13, 4)
        self.n_snow = 50
        self.sw = rng.uniform(0, ctx.w, self.n_snow)
        self.swy = rng.uniform(0, ctx.h, self.n_snow)

    def _ridge(self, x, pars, base):
        ph, am = pars
        y = np.full_like(x, base)
        for k in range(4):
            y -= am[k] * np.abs(np.sin(x * (k + 1) * 0.7 + ph[k]))
        return y

    def render(self, i, tn, t):
        ctx = self.ctx
        g = clip01(ctx.yy * -0.5 + 0.5)
        img = (f32((0.01, 0.02, 0.05)) * g[..., None] + f32((0.03, 0.05, 0.10)) * (1 - g[..., None])).copy()
        self.draw_stars(img, self.stars, tn, t, gain=0.9)
        f = ctx.field("aur", octaves=3, base=3, phases=3, persistence=0.55, seed=31)
        n = f.at(t * 0.6, drift=(t * 12, 0), phase_rate=0.16)
        shade = 0.5 + 0.5 * np.sin(ctx.xx * 9 + n * 8 + t * 0.6)
        topfall = clip01(1.4 + ctx.yy)
        xrow = ctx.xx[0]
        for k, (yb, wdt, spd, ph) in enumerate(self.curt):
            wave = yb + 0.18 * np.sin(xrow * spd + t * 0.5 * (1 + k * 0.3) + ph)
            curtain = np.exp(-((ctx.yy - wave[None, :]) / wdt) ** 2)
            shimmer = 0.3 + 0.7 * (0.35 + 0.65 * n) * (0.4 + 0.6 * shade)
            col = self.ac * (1 - k * 0.25) + self.ac2 * (k * 0.25)
            img += (curtain * shimmer * topfall)[..., None] * col * 0.55
        hor = 0.55
        xrow2 = ctx.xx[0]
        r2 = self._ridge(xrow2 + 2.0, self.rp2, hor - 0.12)[None, :]
        r1 = self._ridge(xrow2, self.rp1, hor - 0.04)[None, :]
        img[ctx.yy > r2] = f32((0.008, 0.012, 0.02))
        img[ctx.yy > r1] = f32((0.004, 0.006, 0.012))
        py = (self.swy + t * 8) % ctx.h
        px = (self.sw + t * 3 + 4 * np.sin(t + self.swy)) % ctx.w
        splat_points(img, px, py, np.full((self.n_snow, 3), 0.5, np.float32), ctx.h, ctx.w, gain=0.35)
        return clip01(img)


# ---------------------------------------------------------------- 8. cityrain

class CityRainScene(Scene):
    id, label = "cityrain", "Midnight City Rain"
    cam_k, default_motion = 0.5, "dolly_in"

    def __init__(self, ctx):
        super().__init__(ctx)
        rng = ctx.rng
        self.nb = 58
        self.bx = rng.uniform(0, ctx.w, self.nb)
        self.by = rng.uniform(0, ctx.h * 0.72, self.nb)
        self.br = rng.uniform(6, max(10, ctx.w * 0.045), self.nb)
        self.bvx = rng.uniform(-3.5, 3.5, self.nb)
        warm, neon = rng.random(self.nb) > 0.45, ~ (rng.random(self.nb) > 0.45)
        cols = np.zeros((self.nb, 3), np.float32)
        cols[warm] = f32((1.0, 0.62, 0.25))
        cols[neon] = f32((0.35, 0.75, 1.0))
        jitter = rng.uniform(0.85, 1.15, (self.nb, 3)).astype(np.float32)
        self.bcols = np.clip(cols * jitter, 0, 1)
        self.bph = rng.uniform(0, 6.28, self.nb)
        # bake a rain-streak texture
        tex = Image.new("L", (ctx.w, ctx.h), 0)
        dr = ImageDraw.Draw(tex)
        for _ in range(int(ctx.w * ctx.h / 900)):
            x, y = rng.uniform(0, ctx.w), rng.uniform(0, ctx.h)
            ln = rng.uniform(6, 18)
            sl = ln * 0.18
            g = int(rng.uniform(90, 200))
            dr.line([(x, y), (x + sl, y + ln)], fill=g, width=1)
        self.raintex = (np.asarray(tex, np.float32) / 255.0)

    def render(self, i, tn, t):
        ctx = self.ctx
        g = clip01(ctx.yy * 0.5 + 0.5)
        img = (f32((0.02, 0.025, 0.06)) * (1 - g[..., None]) + f32((0.03, 0.045, 0.09)) * g[..., None]).copy()
        img += f32((0.06, 0.04, 0.12)) * clip01(1 - g)[..., None] * 0.8
        bx = (self.bx + t * self.bvx) % ctx.w
        breathe = 0.78 + 0.30 * np.sin(t * 1.3 + self.bph)
        cols = self.bcols * breathe[:, None]
        bokeh = np.zeros_like(img)
        splat_blobs(bokeh, bx, self.by, cols, self.br.astype(np.int32), ctx.h, ctx.w)
        img += bokeh * 0.95
        # "wet street" reflection: flipped & dimmed bokeh below horizon
        hor_px = int(ctx.h * 0.74)
        refl = np.zeros_like(bokeh)
        src = bokeh[:hor_px][::-1]
        fit = min(refl.shape[0] - hor_px, src.shape[0])
        if fit > 0:
            refl[hor_px:hor_px + fit] = src[:fit] * 0.5
        img += refl
        # rain layers (two parallax speeds of the same streak texture)
        off1 = int((t * ctx.h * 2.1)) % ctx.h
        off2 = int((t * ctx.h * 1.3)) % ctx.h
        slant1 = int(t * 40) % ctx.w
        r1 = np.roll(self.raintex, (off1, slant1), (0, 1))
        r2 = np.roll(self.raintex, (off2, -slant1 // 2), (0, 1))
        rain = r1 + r2 * 0.5
        img += rain[..., None] * f32((0.55, 0.65, 0.8)) * (0.5 + 0.45 * ctx.energy)
        return clip01(img)


# ---------------------------------------------------------------- 9. dunes

class DunesScene(Scene):
    id, label = "dunes", "Sunset Dunes"
    cam_k, default_motion = 0.5, "pan"

    def __init__(self, ctx):
        super().__init__(ctx)
        self.sand_lo = f32((0.42, 0.20, 0.11))
        self.sand_hi = f32((0.94, 0.60, 0.30))
        self.sky_lo = f32((0.99, 0.55, 0.30))
        self.sky_hi = f32((0.42, 0.22, 0.50))

    def render(self, i, tn, t):
        ctx = self.ctx
        sx, sy = ctx.view(tn, self.cam_k)
        f = ctx.field("dune", octaves=3, base=2, phases=3, persistence=0.5, seed=61)
        n = f.at(t * 0.3, drift=(t * 6, 0), phase_rate=0.08)
        n2 = np.roll(n, (0, 53), (0, 1))
        y01 = sy * 0.5 + 0.5
        # broad dune crest line, slowly breathing with the noise field
        crest = (0.60
                 + 0.10 * np.sin(sx * 1.3 + n * 3.4)
                 + 0.07 * np.sin(sx * 2.9 + n2 * 5.2 + 1.7)
                 + 0.03 * np.sin(sx * 6.1 + n * 8.5 + 3.4))
        ground = smoothstep(crest, crest + 0.015, y01)
        slope = crest - np.roll(crest, (0, -5), (0, 1))
        shade = clip01(0.55 - slope * 7.0)
        sand = self.sand_lo * (1 - shade[..., None]) + self.sand_hi * shade[..., None]
        sand *= (0.80 + 0.28 * y01[..., None])
        rip = 0.008 * np.sin(sy * 45 + n * 15 + t * 0.3)
        sand *= (1 + rip[..., None] * ground[..., None])
        # sky
        g = clip01(ctx.yy * 0.5 + 0.62)
        sky = self.sky_hi * g[..., None] + self.sky_lo * (1 - g[..., None])
        sun = radial(ctx.xx, ctx.yy, 0.25, -0.30 + 0.06 * np.sin(t * 0.2), 0.08)
        sky += sun[..., None] * f32((1.0, 0.8, 0.5)) * 0.9
        sky += radial(ctx.xx, ctx.yy, 0.25, -0.3, 0.9)[..., None] * f32((0.9, 0.4, 0.3)) * 0.35
        img = sky * (1 - ground[..., None]) + sand * ground[..., None]
        # heat haze shimmer near the crest
        haze = smoothstep(0.05, 0.0, np.abs(y01 - crest)) * ground
        img += (haze * 0.05 * (0.5 + 0.5 * np.sin(t * 6 + ctx.xx * 30)))[..., None]
        return clip01(img)


# --------------------------------------------------------------- 10. stream

class DataStreamScene(Scene):
    id, label = "datastream", "Data Stream"
    cam_k, default_motion = 0.3, "dolly_in"

    GLYPHS = "0101XZKW<>[]{}#$%&*+-/|=~^:;7ΔΣ"

    def __init__(self, ctx):
        super().__init__(ctx)
        rng = ctx.rng
        self.cw = max(14, ctx.w // 40)
        self.ncol = ctx.w // self.cw + 1
        try:
            from PIL import ImageFont
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
                                      max(10, self.cw - 3))
        except Exception:
            from PIL import ImageFont
            font = ImageFont.load_default()
        tex = Image.new("L", (ctx.w, ctx.h), 0)
        dr = ImageDraw.Draw(tex)
        rows = ctx.h // self.cw + 1
        for c in range(self.ncol):
            for r in range(rows):
                if rng.random() > 0.50:
                    continue
                ch = self.GLYPHS[int(rng.integers(0, len(self.GLYPHS)))]
                g = int(rng.uniform(70, 210))
                dr.text((c * self.cw + 1, r * self.cw), ch, fill=g, font=font)
        self.tex = np.asarray(tex, np.float32) / 255.0
        self.spd = rng.uniform(30, 130, self.ncol) * (0.6 + ctx.energy)
        glowv = f32(ctx.accent(0, (0.2, 1.0, 0.45)))
        self.gcol = glowv
        self.colidx = (ctx.pix_x[0] // self.cw).astype(np.int32)
        self.head_ph = rng.uniform(0, 1, self.ncol)
        self.glitch_at = rng.uniform(1.5, 3.0)
        self.glitch_rows = None

    def render(self, i, tn, t):
        ctx = self.ctx
        offs = (t * self.spd).astype(np.int32)
        yv = ctx.pix_y[:, 0].astype(np.int32)
        ty = (yv[:, None] - offs[self.colidx][None, :]) % ctx.h
        grid = self.tex[ty, ctx.pix_x.astype(np.int32)]
        head = (t * self.spd / ctx.h + self.head_ph) % 1.0
        head_y = head[self.colidx][None, :] * ctx.h
        dhead = np.abs(ctx.pix_y - head_y) / (ctx.h * 0.5)
        bright = np.exp(-dhead * 6.0)
        if self.glitch_at < t:
            self.glitch_at = t + self.ctx.rng.uniform(1.0, 2.5)
            rows = self.ctx.rng.integers(0, ctx.h, 6)
            self.glitch_rows = rows
        if self.glitch_rows is not None:
            grid[self.glitch_rows, :] = np.roll(grid[self.glitch_rows, :], int(ctx.w * 0.02), axis=1)
        img = (grid * (0.5 + bright * 1.4))[..., None] * self.gcol
        img += (bright * grid > 0.35)[..., None] * f32((0.7, 1.0, 0.8)) * 0.9
        f = ctx.field("dst", octaves=3, base=2, phases=3, persistence=0.5, seed=17)
        img *= (0.82 + 0.4 * f.at(t, phase_rate=0.3)[..., None])
        return clip01(img * 1.25)


# --------------------------------------------------------------- 11. clouds

class CloudScene(Scene):
    id, label = "clouds", "Sky Cloudscape"
    cam_k, default_motion = 0.5, "dolly_in"

    def __init__(self, ctx):
        super().__init__(ctx)
        day = 1 - ctx.night
        self.sky_hi = f32((0.25, 0.5, 0.85)) * (0.25 + 0.75 * day) + f32((0.5, 0.3, 0.7)) * ctx.night * 0.4
        self.sky_lo = f32((0.8, 0.68, 0.75)) if ctx.night > 0.5 else f32((0.62, 0.74, 0.92))
        self.cl_lo = f32((0.38, 0.4, 0.55)) * (0.4 + 0.6 * day)
        self.cl_hi = f32((1.02, 0.98, 0.95))
        self.sun_pos = (float(ctx.rng.uniform(-0.4, 0.4)), float(ctx.rng.uniform(-0.7, -0.2)))
        self.sun_c = f32((1.0, 0.85, 0.6))

    def render(self, i, tn, t):
        ctx = self.ctx
        sx, sy = ctx.view(tn, self.cam_k)
        f = ctx.field("cld", octaves=5, base=2, phases=3, persistence=0.68, seed=53)
        n = f.at(t * 0.5, drift=(t * 14, t * 2), phase_rate=0.12)
        body = smoothstep(0.46, 0.70, n + sy * 0.06)
        off = 4
        n_o = np.roll(n, (-off, -off), (0, 1))
        lit = clip01((n - n_o) * 4 + 0.55)
        cloud = self.cl_lo * (1 - lit[..., None]) + self.cl_hi * lit[..., None]
        g = clip01(ctx.yy * 0.5 + 0.5)
        sky = self.sky_hi * (1 - g[..., None]) + self.sky_lo * g[..., None]
        sun = radial(sx, sy, self.sun_pos[0], self.sun_pos[1], 0.03)
        halo = radial(sx, sy, self.sun_pos[0], self.sun_pos[1], 0.4)
        sky += self.sun_c * (sun * 0.6 + halo * 0.16)[..., None]
        img = sky * (1 - body[..., None]) + cloud * body[..., None]
        img += (body * clip01(1 - np.abs(sy + 0.2)) * 0.6)[..., None] * self.sun_c * 0.22
        return clip01(img)


# ------------------------------------------------------------ 12. fireflies

class FirefliesScene(Scene):
    id, label = "fireflies", "Firefly Hollow"
    cam_k, default_motion = 0.7, "handheld"

    def __init__(self, ctx):
        super().__init__(ctx)
        rng = ctx.rng
        self.n = 46
        self.anchor = np.stack([rng.uniform(-ctx.aspect * 0.9, ctx.aspect * 0.9, self.n),
                                rng.uniform(-0.8, 0.8, self.n)], axis=1)
        self.ax = rng.uniform(0.05, 0.3, self.n)
        self.ay = rng.uniform(0.05, 0.25, self.n)
        self.wx = rng.uniform(0.3, 1.1, self.n)
        self.wy = rng.uniform(0.3, 1.0, self.n)
        self.px = rng.uniform(0, 6.28, self.n)
        self.py = rng.uniform(0, 6.28, self.n)
        self.blink = rng.uniform(1.2, 3.2, self.n)
        self.bp = rng.uniform(0, 6.28, self.n)
        glow = f32(ctx.accent(0, (0.75, 1.0, 0.35)))
        jitter = rng.uniform(0.7, 1.2, (self.n, 3)).astype(np.float32)
        self.cols = np.clip(glow * jitter, 0, 1)
        self.trail = np.zeros((ctx.h, ctx.w, 3), np.float32)
        # faint tree silhouettes
        self.trunks = rng.uniform(0, ctx.w, 6), rng.uniform(ctx.w * 0.015, ctx.w * 0.035, 6)

    def render(self, i, tn, t):
        ctx = self.ctx
        g = clip01(ctx.yy * 0.5 + 0.5)
        img = (f32((0.01, 0.03, 0.02)) * (1 - g[..., None]) + f32((0.02, 0.05, 0.03)) * g[..., None]).copy()
        mist = ctx.field("ffm", octaves=3, base=2, phases=3, persistence=0.55, seed=71)
        img += (mist.at(t * 0.4, drift=(t * 8, 0), phase_rate=0.1) * 0.13)[..., None] * f32((0.4, 0.7, 0.5))
        moon = radial(ctx.xx, ctx.yy, -ctx.aspect * 0.55, -0.75, 0.06)
        img += moon[..., None] * f32((0.35, 0.45, 0.4))
        img += radial(ctx.xx, ctx.yy, 0.1, 0.9, 1.2)[..., None] * f32((0.05, 0.09, 0.05))
        for tx, tw_ in zip(*self.trunks):
            col = clip01(1 - np.abs(ctx.pix_x - tx) / tw_)
            img *= (1 - (col[..., None] * clip01((ctx.yy + 0.2) * 3)[..., None]) * 0.62)
        xs = self.anchor[:, 0] + self.ax * np.sin(self.wx * t + self.px)
        ys = self.anchor[:, 1] + self.ay * np.cos(self.wy * t + self.py)
        pulse = np.clip(np.sin(self.blink * t + self.bp), 0, 1) ** 1.4
        pxp, pyp = ctx.project_pts(xs, ys, tn, parallax=0.85)
        frame_glow = np.zeros_like(img)
        splat_blobs(frame_glow, pxp, pyp, self.cols * (pulse[:, None] * 2.6),
                    np.full(self.n, max(2, ctx.w // 220)), ctx.h, ctx.w)
        self.trail *= 0.90
        self.trail = np.maximum(self.trail * 0.97, self.trail + frame_glow * 0.25)
        img += self.trail * 0.7 + frame_glow
        return clip01(img)


# ------------------------------------------------------------------ registry

SCENES: dict[str, type[Scene]] = {
    s.id: s for s in (NebulaScene, LiquidScene, NeonGridScene, EmberScene, OceanScene,
                      InkScene, AuroraScene, CityRainScene, DunesScene, DataStreamScene,
                      CloudScene, FirefliesScene)
}

SCENE_META = {k: {"label": v.label, "default_motion": v.default_motion} for k, v in SCENES.items()}
