"""T2V·LAB — FastAPI backend.

Routes:
  /                     studio UI
  /static/**            assets
  /media/**             generated videos & thumbnails
  /api/config           engine/preset catalog + cloud provider status
  /api/enhance          prompt → cinematic enhancement (JSON)
  /api/generate         queue a render
  /api/jobs[. /{id}]    gallery + status
  /api/settings         API token management (server-side only)
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .camera import MOTIONS
from .director import enhance
from .engine_procedural import ASPECTS, DURATIONS, FPS, QUALITY_CRF
from .scenes import SCENE_META
from .jobs import JobManager
from . import engine_cloud

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "static"
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
SETTINGS_FILE = DATA / "settings.json"
MAX_PROMPT = 600

app = FastAPI(title="T2V·LAB", docs_url=None, redoc_url=None, openapi_url=None)
_jobs = JobManager(DATA)
_settings_lock = threading.Lock()


def _load_settings() -> dict:
    if SETTINGS_FILE.exists():
        try:
            return json.loads(SETTINGS_FILE.read_text())
        except Exception:
            pass
    return {}


def _save_settings(s: dict):
    SETTINGS_FILE.write_text(json.dumps(s))
    try:
        os.chmod(SETTINGS_FILE, 0o600)
    except OSError:
        pass


# ------------------------------------------------------------------- models

class GenerateIn(BaseModel):
    prompt: str = Field(min_length=2, max_length=MAX_PROMPT)
    scene: str | None = None
    motion: str = "auto"
    aspect: str = "16:9"
    quality: str = "draft"
    duration_s: int = 5
    seed: int | None = None
    letterbox: bool = False
    engine: str = "local"            # local | replicate | fal
    cloud_model: str | None = None


class EnhanceIn(BaseModel):
    prompt: str = Field(min_length=1, max_length=MAX_PROMPT)


class SettingsIn(BaseModel):
    replicate_token: str | None = None
    fal_key: str | None = None


# ------------------------------------------------------------------- pages

app.mount("/media", StaticFiles(directory=DATA / "outputs"), name="media")
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/", include_in_schema=False)
def index():
    return FileResponse(STATIC / "index.html")


# --------------------------------------------------------------------- api

@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/config")
def config():
    settings = _load_settings()
    clouds = engine_cloud.provider_status(settings)
    return {
        "fps": FPS,
        "motions": [{"id": k, **v} for k, v in MOTIONS.items()],
        "scenes": [{"id": k, **v} for k, v in SCENE_META.items()],
        "aspects": {k: list(v) for k, v in ASPECTS.items()},
        "qualities": list(QUALITY_CRF),
        "durations": list(DURATIONS),
        "defaults": {"aspect": "16:9", "quality": "draft", "duration_s": 5, "motion": "auto"},
        "cloud": clouds,
        "cloud_models": {
            "replicate": engine_cloud.DEFAULT_REPLICATE_MODEL,
            "fal": engine_cloud.DEFAULT_FAL_MODEL,
        },
        "prompt_ideas": PROMPT_IDEAS,
    }


@app.post("/api/enhance")
def api_enhance(body: EnhanceIn):
    return {"enhanced": enhance(body.prompt)}


@app.post("/api/generate")
def generate(body: GenerateIn):
    if body.engine == "local":
        if body.aspect not in ASPECTS:
            raise HTTPException(400, "unknown aspect")
        if body.quality not in QUALITY_CRF:
            raise HTTPException(400, "unknown quality")
        if body.scene and body.scene not in SCENE_META:
            raise HTTPException(400, "unknown scene")
        if body.motion not in MOTIONS:
            raise HTTPException(400, "unknown motion")
        if body.duration_s not in DURATIONS:
            raise HTTPException(400, f"duration must be one of {DURATIONS}")
    else:
        raise HTTPException(400, "cloud generation is queued via /api/generate_cloud")
    return _jobs.submit(body.model_dump(exclude={"engine", "cloud_model"}))


@app.post("/api/generate_cloud")
def generate_cloud(body: GenerateIn):
    settings = _load_settings()
    status = engine_cloud.provider_status(settings)
    prov = status.get(body.engine)
    if body.engine not in ("replicate", "fal"):
        raise HTTPException(400, "engine must be 'replicate' or 'fal'")
    if not prov or not prov["has_key"]:
        raise HTTPException(400, f"no {body.engine} API token saved — open Settings")
    if not prov["reachable"]:
        raise HTTPException(502, f"cannot reach {body.engine} from this server right now")

    job = _jobs.create(body.prompt, {"engine": body.engine, "cloud_model": body.cloud_model})

    def _cloud_run():
        _jobs._update(job["id"], status="running")
        model = body.cloud_model or (
            engine_cloud.DEFAULT_REPLICATE_MODEL if body.engine == "replicate"
            else engine_cloud.DEFAULT_FAL_MODEL)
        out = DATA / "outputs" / f"{job['id']}.mp4"
        try:
            if body.engine == "replicate":
                engine_cloud.run_replicate(body.prompt, model, settings["replicate_token"], out)
            else:
                engine_cloud.run_fal(body.prompt, model, settings["fal_key"], out)
            _jobs._update(job["id"], status="done", progress=1.0,
                          video_url=f"/media/{out.name}", thumb_url=None,
                          meta={"scene": "cloud", "scene_label": f"{body.engine} · {model}",
                                "motion": "ai", "motion_label": "AI model",
                                "enhanced": body.prompt, "seed": None,
                                "video": out.name, "thumb": None})
        except Exception as e:  # noqa: BLE001
            _jobs._update(job["id"], status="error", error=str(e)[:500])
    threading.Thread(target=_cloud_run, daemon=True).start()
    return job


@app.get("/api/jobs")
def list_jobs(limit: int = 48):
    return _jobs.list(min(96, max(1, limit)))


@app.get("/api/jobs/{jid}")
def get_job(jid: str):
    job = _jobs.get(jid)
    if not job:
        raise HTTPException(404, "job not found")
    return job


@app.delete("/api/jobs/{jid}")
def delete_job(jid: str):
    if not _jobs.remove(jid):
        raise HTTPException(404, "job not found")
    return {"ok": True}


@app.get("/api/settings")
def get_settings():
    s = _load_settings()
    return {"has_replicate": bool(s.get("replicate_token")),
            "has_fal": bool(s.get("fal_key"))}


@app.post("/api/settings")
def set_settings(body: SettingsIn):
    with _settings_lock:
        s = _load_settings()
        if body.replicate_token is not None:
            tok = body.replicate_token.strip()
            if tok:
                s["replicate_token"] = tok
            else:
                s.pop("replicate_token", None)
        if body.fal_key is not None:
            tok = body.fal_key.strip()
            if tok:
                s["fal_key"] = tok
            else:
                s.pop("fal_key", None)
        _save_settings(s)
    engine_cloud.provider_status._cache = None  # reset probe cache
    return {"ok": True, **get_settings()}


PROMPT_IDEAS = [
    "a violet nebula blooming with newborn stars, slow deep-space drift",
    "golden desert dunes at sunset, heat shimmer rising off the sand",
    "bioluminescent deep ocean, god rays and drifting marine snow",
    "rainy tokyo alley at midnight, neon bokeh and wet streets",
    "liquid chrome flowing like mercury, iridescent holographic sheen",
    "synthwave sun setting over a chrome grid, retro 80s outrun",
    "aurora borealis dancing over silent snowy ridges, falling snow",
    "black ink blooming across wet rice paper, zen and minimal",
    "volcanic embers rising through darkness, slow intense burn",
    "soft cumulus clouds sailing over a golden hour sky, dreamy drift",
    "matrix code cascading in a dark digital void, green glyphs",
    "fireflies orbiting in a moonlit forest hollow, soft trails",
    "a phoenix rising through deep blue fire and violet embers",
    "frozen arctic night, teal aurora over black ice mountains",
    "crimson ink swirling underwater like smoke in slow motion",
]
