<div align="center">

# 🎬 T2V·LAB — Text → Video Studio

**Type a prompt. Get a graded, camera-choreographed 24-fps mp4 — in seconds, on your machine.**

</div>

T2V·LAB is a Higgsfield-style text-to-video studio: a polished web app where you describe a
shot, pick a **world** and a **camera motion** (dolly, orbit, crash-zoom, whip-pan, FPV…),
and get back a downloadable H.264 clip with a persistent gallery, prompt enhancement
("director's cut"), seeds, remixing, and a render queue.

It ships with two engine paths:

| Engine | What it is | Needs |
| --- | --- | --- |
| **Local motion engine** (default) | A real-time procedural render core (numpy → ffmpeg) with **12 cinematic worlds**, film grain, bloom, vignette, chromatic aberration, exposure breathing, watermark, optional cinemascope bars | nothing — runs on CPU |
| **Cloud AI connectors** | Full diffusion-model T2V — Kling / Hunyuan / MiniMax-class models via **Replicate** or **fal.ai** | your API token (Settings ⚙) |

> Honest positioning: matching Higgsfield's neural video quality requires large diffusion
> video models. This repo gives you the *product* (studio UI, motion control, prompt
> directing, gallery) plus an instant local engine, and wires in real diffusion video the
> moment you add an API key. No GPU needed until then.

---

## Quickstart

```bash
pip install -r requirements.txt   # numpy, pillow, imageio-ffmpeg (bundles ffmpeg), fastapi, uvicorn
./run.sh                          # → http://0.0.0.0:8000  (PORT env to change)
```

Open the studio, type e.g. *"rainy tokyo alley at midnight, neon bokeh and wet streets"*,
hit **✦ Generate video** (or ⌘/Ctrl+Enter). A 4–8s draft clip (640×360) lands in the
gallery in roughly **1×–3× realtime** on a 2-core box.

## The studio

- **Prompt box** — rotating cinematic ideas, 🎲 surprise prompt, ✦ *director's cut* toggle
  (auto rewrites your prompt as a film shot: shot type, lens, lighting, grade).
- **World** — 12 directed worlds (auto-picked from your prompt, or force one):
  Deep Space Nebula · Liquid Chrome · Synthwave Grid · Fire & Embers · Deep Ocean ·
  Ink Bloom · Aurora Ridge · Midnight City Rain · Sunset Dunes · Data Stream ·
  Sky Cloudscape · Firefly Hollow.
- **Camera motion** — Tripod, Dolly In/Out, Lateral Pan, Orbit, Crash Zoom (double punch),
  Whip Pan, FPV Drone, Handheld — or *Auto* (each world has a signature default).
- **Frame** — 16:9 / 9:16 / 1:1, 3–8s, Draft/Standard/HD-720p, cinemascope bars, seed control.
- **Gallery** — hover-to-play cards, lightbox with prompt + metadata, **remix** (reloads
  prompt & seed), download, delete. Persisted across restarts (`data/`, git-ignored).

## Under the hood

```
prompt ──▶ director.py ──▶ scene + palette + energy + enhanced prompt
                 │
                 ▼
 engine_procedural.py   (job queue: jobs.py, 1 render at a time)
   ├─ NoiseLib: temporally-coherent fBm (phase-blended, drifted octaves)
   ├─ Camera: eases + noise curves → (scale, dx, dy, rot) per frame
   ├─ Scene: 12 renderers (fields, particles, caustics, bokeh, glyphs…)
   ├─ PostFX: bloom → chromatic aberration → vignette → film grain → watermark → bars
   └─ ffmpeg pipe (imageio-ffmpeg) → H.264 yuv420p, faststart
```

Text is interpreted locally (no LLM): keyword→scene routing, color-word→hex-accent palettes,
energy/night heuristics, deterministic hash fallbacks so a prompt always seeds a coherent look.

## Cloud engines (real AI video)

Settings ⚙ → paste a **Replicate** token or **fal.ai** key (stored server-side in
`data/settings.json`, chmod 600, never committed). Then pick the engine in the composer:

- Replicate default: `minimax/video-01` (Kling/Hunyuan slugs work too)
- fal.ai default: `fal-ai/kling-video/v1/standard/text-to-video`

Connectors live in `server/engine_cloud.py` (plain HTTPS, no SDK). If the server can't
reach a provider, the UI shows the engine as locked/unreachable instead of failing later.

## API

| Route | Purpose |
| --- | --- |
| `GET /api/config` | worlds, motions, presets, cloud status, prompt ideas |
| `POST /api/generate` | queue a local render `{prompt, scene?, motion, aspect, quality, duration_s, seed?, letterbox}` |
| `POST /api/generate_cloud` | queue a Replicate/fal render |
| `GET /api/jobs`, `GET /api/jobs/{id}` | gallery + status (polled by the UI) |
| `DELETE /api/jobs/{id}` | remove clip + files |
| `POST /api/enhance` | director's-cut prompt rewrite |
| `GET/POST /api/settings` | provider token management (presence only, never values) |

## Roadmap to "Higgsfield-class"

- [ ] Motion brush / trajectory painting over a still
- [ ] Image-to-video (upload a frame as keyframe)
- [ ] Multi-shot stitching (auto-edited sequences)
- [ ] Audio ambience per world
- [ ] Wildcard world: style-transfer the prompt into any world palette
