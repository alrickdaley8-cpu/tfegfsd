"""Optional cloud AI-video connectors (real diffusion-model text-to-video).

These use plain HTTPS via urllib — no SDKs. They're dormant unless the user
adds an API token in Settings *and* the host can reach the provider. Supported:

  • Replicate — e.g. "minimax/video-01", "tencent/hunyuan-video", "kwaivgi/kling-v2.0"
  • fal.ai    — e.g. "fal-ai/minimax-video", "fal-ai/kling-video/v1/standard/text-to-video",
                      "fal-ai/hunyuan-video", "fal-ai/ltx-video"
"""
from __future__ import annotations

import json
import time
import urllib.request
import urllib.error
from pathlib import Path

DEFAULT_REPLICATE_MODEL = "minimax/video-01"
DEFAULT_FAL_MODEL = "fal-ai/kling-video/v1/standard/text-to-video"


def _http(method: str, url: str, headers: dict, payload: dict | None, timeout=30):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def _probe(url: str, timeout: float = 4.0) -> bool:
    try:
        req = urllib.request.Request(url, method="HEAD")
        urllib.request.urlopen(req, timeout=timeout)
        return True
    except Exception:
        try:
            urllib.request.urlopen(url, timeout=timeout)
            return True
        except Exception:
            return False


def provider_status(settings: dict) -> dict:
    """Report which providers are configured + reachable (short cached probe)."""
    now = time.time()
    cache = getattr(provider_status, "_cache", None)
    if cache and now - cache[0] < 60:
        return cache[1]
    out = {}
    for name, token_key, host in (
        ("replicate", "replicate_token", "https://api.replicate.com"),
        ("fal", "fal_key", "https://queue.fal.run"),
    ):
        has_key = bool(settings.get(token_key))
        out[name] = {"has_key": has_key, "reachable": _probe(host) if has_key else False}
    provider_status._cache = (now, out)
    return out


def run_replicate(prompt: str, model: str, token: str, out_path: Path,
                  extra_input: dict | None = None) -> Path:
    headers = {"Authorization": f"Bearer {token}", "Prefer": "wait"}
    payload = {"input": {"prompt": prompt, **(extra_input or {})}}
    pred = _http("POST", f"https://api.replicate.com/v1/models/{model}/predictions",
                 headers, payload, timeout=60)
    url = pred["urls"]["get"]
    for _ in range(240):
        time.sleep(5)
        s = _http("GET", url, headers, None, timeout=30)
        if s["status"] == "succeeded":
            out = s.get("output")
            video_url = out if isinstance(out, str) else (out[-1] if isinstance(out, list) else out.get("video"))
            urllib.request.urlretrieve(video_url, out_path)
            return out_path
        if s["status"] in ("failed", "canceled"):
            raise RuntimeError(f"replicate: {s.get('error') or s['status']}")
    raise TimeoutError("replicate: timed out after 20 minutes")


def run_fal(prompt: str, model: str, key: str, out_path: Path,
            extra_input: dict | None = None) -> Path:
    headers = {"Authorization": f"Key {key}"}
    payload = {"prompt": prompt, **(extra_input or {})}
    sub = _http("POST", f"https://queue.fal.run/{model}", headers, payload, timeout=60)
    status_url = sub["status_url"]
    result_url = sub["response_url"]
    for _ in range(240):
        time.sleep(5)
        s = _http("GET", status_url, headers, None, timeout=30)
        if s.get("status") == "COMPLETED":
            res = _http("GET", result_url, headers, None, timeout=30)
            video_url = res.get("video", {}).get("url") or res.get("video_url")
            if not video_url:
                raise RuntimeError(f"fal: cannot find video in response keys {list(res)}")
            urllib.request.urlretrieve(video_url, out_path)
            return out_path
        if s.get("status") in ("FAILED", "ERROR"):
            raise RuntimeError("fal: generation failed")
    raise TimeoutError("fal: timed out after 20 minutes")
