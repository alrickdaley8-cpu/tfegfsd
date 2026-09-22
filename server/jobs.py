"""Job registry + a single-render-at-a-time queue (renders are CPU-bound)."""
from __future__ import annotations

import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .engine_procedural import render_video, RenderCancelled


class JobManager:
    def __init__(self, data_dir: Path):
        self.dir = data_dir
        self.out = data_dir / "outputs"
        self.out.mkdir(parents=True, exist_ok=True)
        self.file = data_dir / "jobs.json"
        self.lock = threading.RLock()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="render")
        self.jobs: dict[str, dict] = {}
        self._load()

    # --------------------------------------------------------- persistence
    def _load(self):
        if self.file.exists():
            try:
                for j in json.loads(self.file.read_text()):
                    if j["status"] in ("queued", "running"):
                        j["status"], j["error"] = "error", "interrupted by restart"
                    self.jobs[j["id"]] = j
            except Exception:
                self.jobs = {}

    def _save(self):
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps(list(self.jobs.values()), indent=1))
        tmp.replace(self.file)

    def _update(self, jid: str, **kw):
        with self.lock:
            self.jobs[jid].update(kw)
            self._save()

    # ------------------------------------------------------------- control
    def create(self, prompt: str, params: dict) -> dict:
        """Register a job without scheduling it (for externally-run work)."""
        jid = uuid.uuid4().hex[:10]
        job = {
            "id": jid, "prompt": prompt, "status": "queued",
            "progress": 0.0, "error": None, "created": time.time(),
            "params": params,
            "video_url": None, "thumb_url": None, "meta": None,
        }
        with self.lock:
            self.jobs[jid] = job
            self._save()
        return job

    def submit(self, spec: dict) -> dict:
        job = self.create(spec["prompt"], {k: v for k, v in spec.items() if k != "prompt"})
        self.pool.submit(self._run, job["id"], spec)
        return job

    def _run(self, jid: str, spec: dict):
        self._update(jid, status="running", started=time.time())
        try:
            def prog(i, n):
                self.jobs[jid]["progress"] = round(i / max(1, n), 3)
            meta = render_video(spec, self.out, jid, on_progress=prog)
            self._update(jid, status="done", progress=1.0, finished=time.time(),
                         video_url=f"/media/{meta['video']}",
                         thumb_url=f"/media/{meta['thumb']}" if meta.get("thumb") else None,
                         meta=meta)
        except RenderCancelled:
            self._update(jid, status="error", error="cancelled", finished=time.time())
        except Exception as e:  # noqa: BLE001
            self._update(jid, status="error", error=str(e)[:500], finished=time.time())

    # --------------------------------------------------------------- query
    def get(self, jid: str) -> dict | None:
        with self.lock:
            j = self.jobs.get(jid)
            return dict(j) if j else None

    def list(self, limit: int = 48) -> list[dict]:
        with self.lock:
            jobs = sorted(self.jobs.values(), key=lambda j: -j["created"])
            return [dict(j) for j in jobs[:limit]]

    def remove(self, jid: str) -> bool:
        with self.lock:
            if jid not in self.jobs:
                return False
            job = self.jobs.pop(jid)
            for key in ("video", "thumb"):
                name = (job.get("meta") or {}).get(key)
                if name:
                    try:
                        (self.out / name).unlink(missing_ok=True)
                    except Exception:
                        pass
            for suffix in (".mp4", ".jpg"):
                (self.out / f"{jid}{suffix}").unlink(missing_ok=True)
            self._save()
            return True
