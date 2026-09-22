"""The procedural motion engine: scene + camera + postfx -> h264 mp4."""
from __future__ import annotations

import subprocess
import threading
from pathlib import Path

import numpy as np
from PIL import Image
from imageio_ffmpeg import get_ffmpeg_exe

from .camera import Camera, MOTIONS
from .director import analyze
from .postfx import PostFX
from .scenes import Ctx, SCENES, SCENE_META

FPS = 24

ASPECTS: dict[str, dict[str, tuple[int, int]]] = {
    "16:9": {"draft": (640, 360), "standard": (854, 480), "high": (1280, 720)},
    "9:16": {"draft": (360, 640), "standard": (480, 854), "high": (720, 1280)},
    "1:1":  {"draft": (480, 480), "standard": (576, 576), "high": (720, 720)},
}

QUALITY_CRF = {"draft": 23, "standard": 20, "high": 18}
QUALITY_MAXRATE = {"draft": "3M", "standard": "5M", "high": "9M"}
DURATIONS = (3, 4, 5, 6, 8)


class RenderCancelled(Exception):
    pass


def _ffmpeg_out(path: Path, w: int, h: int, crf: int, maxrate: str) -> subprocess.Popen:
    cmd = [
        get_ffmpeg_exe(), "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(FPS),
        "-i", "pipe:0",
        "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", str(crf),
        "-maxrate", maxrate, "-bufsize", str(int(float(maxrate[:-1]) * 2)) + "M",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        str(path),
    ]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def render_video(spec: dict, out_dir: Path, job_id: str,
                 on_progress=None, cancel: threading.Event | None = None) -> dict:
    prompt: str = spec["prompt"].strip()
    seed = int(spec["seed"]) if spec.get("seed") not in (None, "") else \
        int(np.random.SeedSequence().entropy % (2**31))
    aspect = spec.get("aspect", "16:9")
    quality = spec.get("quality", "draft")
    w, h = ASPECTS.get(aspect, ASPECTS["16:9"]).get(quality, ASPECTS["16:9"]["draft"])
    duration = int(spec.get("duration_s", 5))
    duration = max(DURATIONS[0], min(DURATIONS[-1], duration))
    n_frames = duration * FPS

    analysis = analyze(prompt, seed)
    scene_id = spec.get("scene") if spec.get("scene") in SCENES else analysis["scene"]
    motion = spec.get("motion", "auto")
    if motion not in MOTIONS or motion == "auto":
        motion = SCENE_META[scene_id]["default_motion"]

    ctx = Ctx(h=h, w=w, seed=seed, camera=Camera(motion, h, w, seed),
              accents=[tuple(a) for a in analysis["accents"]],
              energy=analysis["energy"], night=analysis["night"])
    scene = SCENES[scene_id](ctx)
    post = PostFX(h, w, seed, letterbox=bool(spec.get("letterbox")))

    out_dir.mkdir(parents=True, exist_ok=True)
    mp4 = out_dir / f"{job_id}.mp4"
    thumb = out_dir / f"{job_id}.jpg"

    proc = _ffmpeg_out(mp4, w, h, QUALITY_CRF.get(quality, 20),
                       QUALITY_MAXRATE.get(quality, "5M"))
    grain = 0.035 + 0.045 * analysis["energy"]
    chroma = 1.4 if scene_id in ("neon", "cityrain", "datastream", "liquid") else 0.9
    thumb_at = int(n_frames * 0.35)

    if on_progress:
        on_progress(0, n_frames)
    try:
        for i in range(n_frames):
            if cancel is not None and cancel.is_set():
                proc.kill()
                raise RenderCancelled()
            tn = i / max(1, n_frames - 1)
            t = i / FPS
            img = scene.render(i, tn, t)
            img = post.apply(np.clip(img, 0.0, 1.5), t, grain=grain, chroma=chroma)
            frame = (np.clip(img, 0, 1) * 255.0 + 0.5).astype(np.uint8)
            if i == thumb_at:
                Image.fromarray(frame).save(thumb, quality=86)
            proc.stdin.write(frame.tobytes())
            if on_progress and (i % 6 == 0):
                on_progress(i, n_frames)
        proc.stdin.close()
        rc = proc.wait()
        if rc != 0 or not mp4.exists():
            raise RuntimeError(f"ffmpeg exited with {rc}")
    except Exception:
        if proc.poll() is None:
            proc.kill()
        raise
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
    if not thumb.exists():
        thumb = None
    return {
        "scene": scene_id, "scene_label": SCENE_META[scene_id]["label"],
        "motion": motion, "motion_label": MOTIONS[motion]["label"],
        "enhanced": analysis["enhanced"], "seed": seed,
        "w": w, "h": h, "fps": FPS, "duration_s": duration,
        "energy": round(analysis["energy"], 2),
        "video": mp4.name, "thumb": thumb.name if thumb else None,
    }
