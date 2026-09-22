"""Shared film-look post-processing: bloom, grain, vignette, letterbox, CA."""
from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFont


class PostFX:
    def __init__(self, h: int, w: int, seed: int, letterbox: bool = False):
        self.h, self.w = h, w
        self.rng = np.random.default_rng((int(seed) ^ 0xFEED) & 0x7FFFFFFF)
        self.letterbox_on = letterbox
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        nx = xx / (w - 1) * 2 - 1
        ny = yy / (h - 1) * 2 - 1
        r2 = nx ** 2 + ny ** 2
        self.vign = np.clip(1.06 - 0.32 * r2 ** 1.4, 0, 1).astype(np.float32)
        self._wm = self._make_watermark()
        # small downsample buffer for cheap bloom
        self.bs = (max(8, w // 4), max(8, h // 4))

    # ---------------------------------------------------------------- bloom
    def bloom(self, img: np.ndarray, thresh: float = 0.72, strength: float = 0.5) -> np.ndarray:
        bright = np.clip(img - thresh, 0, None) / max(1e-6, 1 - thresh)
        im = Image.fromarray((bright * 255).astype(np.uint8))
        im = im.resize(self.bs, Image.BICUBIC).resize((self.w, self.h), Image.BICUBIC)
        soft = np.asarray(im, np.float32) / 255.0
        return img + soft * strength * np.clip(img.mean() * 2.2, 0.35, 1.0)

    # ------------------------------------------------------------ watermark
    def _make_watermark(self):
        txt = "T2V·LAB"
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 11)
        except Exception:
            font = ImageFont.load_default()
        tmp = Image.new("L", (90, 20), 0)
        d = ImageDraw.Draw(tmp)
        d.text((2, 2), txt, fill=120, font=font)
        bbox = tmp.getbbox()
        if bbox:
            tmp = tmp.crop((bbox[0], 0, bbox[2] + 4, 20))
        wmh, wmw = tmp.size[1], tmp.size[0]
        arr = np.asarray(tmp, np.float32) / 255.0
        return arr[:wmh], wmw, wmh

    def apply(self, img: np.ndarray, t: float, grain: float = 0.045,
              chroma: float = 1.0, exposure: bool = True) -> np.ndarray:
        # exposure breathing — barely perceptible life
        if exposure:
            img = img * (1.0 + 0.018 * np.sin(t * 2.4) + 0.012 * np.sin(t * 0.7 + 2))
        # chromatic aberration (integer shifts, edge-safe via roll+mask)
        if chroma > 0:
            px = int(round(chroma))
            img = img.copy()
            img[:, px:, 0] = img[:, :-px, 0]
            img[:, :-px, 2] = img[:, px:, 2]
        # vignette
        img = img * self.vign[..., None]
        # film grain
        g = self.rng.standard_normal((self.h, self.w), dtype=np.float32) * grain
        img = img + g[..., None] * (0.35 + 0.65 * (1 - np.clip(img.mean(axis=2, keepdims=True), 0, 1)))
        np.clip(img, 0, 1, out=img)
        # watermark
        wm, wmw, wmh = self._wm
        y0, x0 = self.h - wmh - 6, self.w - wmw - 8
        if y0 > 0 and x0 > 0:
            reg = img[y0:y0 + wmh, x0:x0 + wmw]
            img[y0:y0 + wmh, x0:x0 + wmw] = reg * (1 - wm[..., None] * 0.5) + wm[..., None] * 0.5 * 0.75
        if self.letterbox_on:
            bar = int(self.h * 0.085)
            img[:bar] = 0
            img[self.h - bar:] = 0
        return img
