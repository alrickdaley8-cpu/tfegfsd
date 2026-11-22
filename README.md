# NEON BREACH // Arena Survival

A very detailed **multiplayer survival game** that runs entirely in the browser —
a body-cam FPS wave-survival run through a megacity combat grid, with
host-authoritative WebRTC squads for up to four operators.

Everything is procedural: no downloaded assets, no accounts, no telemetry.
Three.js renders a hand-authored megacity (~144×144, merged geometry), the
body-cam overlay, 16 enemy classes with custom 3D rigs, pooled particles,
post-processing, and 100% synthesized audio.

---

## Features

### Solo survival
- **16 enemy classes** across flyers, crawlers, bipeds and heavies — swarm
  drones, razor elites, medics, engineer support, mortar artillery, snipers,
  shield guardians, phase assassins and the warden elite commander.
- **Budgeted wave director**: every chassis has an unlock wave, a threat cost
  and selection weight, so waves escalate along a fair, deliberate curve
  instead of a flat countdown. New classes are telegraphed before their first
  deployment.
- **Weapons & mod workshop**: three weapons (pistol / rifle / shotgun) with a
  90+-entry mod database across 9 build slots, spectrum optics, and live
  build export/import.
- **Dynamic arena**: 9 marked districts, plazas, bridges, ramps, jump pads,
  pulsing plasma vents and sniper lanes — designed for positioning, not just
  reflexes.
- **Body-cam presentation**: handheld camera rig, REC chrome, glass distortion,
  lens dirt, scanlines, motion trails, FXAA and bloom.
- **Balance lab**: a controlled playtest harness with live telemetry (DPS,
  accuracy, TTK, incoming damage) and an automated audit report.
- **Career record**: best score / wave / kills / runs, persisted locally.

### Multiplayer (Netcode 2.0)
- **Host-authoritative WebRTC** star topology. The host owns the simulation;
  joiners send capped-rate state and input.
- **Dual encrypted data channels** per peer: a reliable, ordered control
  channel (lobby, chat, match events) and an unreliable, unordered state
  channel (20&nbsp;Hz snapshots, 30&nbsp;Hz input) — mirroring the industry-standard
  reliability/latency split so a lost state frame can never stall a chat message.
- **Co-op survival** — shared production waves, shared squad score,
  hostile state and squad scaling that grows with the party.
- **Co-op rescue system** — a downed operator leaves a rescue ring at the fall
  site. A teammate within 4.6&nbsp;m can hold <kbd>E</kbd> to channel a
  ~2.2&nbsp;s rescue; the host stands the operator back up at 60% HP with brief
  invulnerability. Auto-redeploy (5&nbsp;s) remains as the fallback if nobody
  comes. PVP mode keeps strict auto-respawn with no rescue.
- **PvP deathmatch** — up to 4 synchronized operators with health, shared
  elimination feed, score limits and time limits, configured by the host.
- **Compressed invite codes** — `NB2.`-prefixed gzip/base64 invite/answer
  exchanges for copy/paste, Paste-and-detect, clipboard and Web Share API.
- **Multi-STUN routing** with an **optional TURN relay** for restrictive NAT,
  route detection, ping/jitter telemetry in the HUD, and reconnect grace.
- **Squad chat** on the reliable channel.

### Mobile & iPad
- Safe-area-aware multi-touch controls (left stick move, right-side drag look,
  FIRE / ADS / JUMP / RELOAD / SWAP / CROUCH / SPRINT / RESCUE / PAUSE),
  simulated-platform detection, landscape enforcement, touch-safe overlays.

---

## Quick start

```bash
# serve locally (zero dependencies, Node 18+)
npm run serve          # → http://0.0.0.0:8080

# or just open index.html in a browser
```

> Note: the WebRTC multiplayer needs an HTTPS context (or `localhost`).
> GitHub Pages satisfies this; opening the file directly from `file://` will
> run solo play but may not expose peer connections.

## Controls

| Input | Action |
| --- | --- |
| `W A S D` | Move |
| Mouse | Look |
| Mouse-to-edge | Free-look steering (when pointer lock unavailable) |
| `Shift` | Sprint |
| `Left click` | Fire |
| `Right click` | Aim / optic |
| `Space` | Jump |
| `R` | Reload |
| `C` | Crouch |
| `1 / 2 / 3` / wheel | Switch weapon |
| `E` | Hold to rescue a downed operator (co-op) |
| `ESC` | Pause / menu |
| `M` | Mute |
| `F` | Toggle lens FX |
| `F1–F6` | Balance Lab controls |

## Dev tooling

The repo ships with a zero-dependency audit stack — the whole game is a single
`index.html`, so verification is static-first:

```bash
npm test              # run everything below
node tools/check.mjs      # syntax parse + id/section/wiring audit
node tools/wave-sim.mjs   # extract the REAL buildWave() and validate pacing
node tools/rescue-sim.mjs # extract the REAL updateRescue() and check the channel math
npm run serve             # zero-dep static server (tools/serve.mjs)
```

`tools/check.mjs` verifies the inline script parses (`node --check`), that every
`getElementById("…")` target exists in markup, that no top-level const name is
duplicated, and that all core sections (multiplayer, wave director, workshop,
rescue, balance lab, boot) are wired.

`tools/wave-sim.mjs` and `tools/rescue-sim.mjs` extract the **real** wave
director and rescue-channel code by brace-balanced parsing and evaluate it in
isolation — so the survival math is tested against production behavior rather
than a copy of it.

CI (`.github/workflows/ci.yml`) runs the same suite on pushes and PRs.

## Design research

The build is grounded in a recorded design study (WebRTC dual-channel networking,
host-authoritative P2P, backpressure-aware state, difficulty-curve and boss-wave
pacing, co-op rescue patterns, performance discipline). See [RESEARCH.md](RESEARCH.md)
for the full notes and source list.

## Architecture

```
index.html          — the entire game (single self-contained entry point)
├─ CSS              — body-cam chrome, menus, HUD, multiplayer panel, touch controls
├─ HTML             — HUD, touch controls, body-cam overlay, menus, multiplayer panel
└─ JS               — single IIFE, sectioned:
   ├─ Util / AudioEngine / Procedural textures
   ├─ Renderer / Scene / PostFX (bloom, glitch, FXAA, lens dirt)
   ├─ PC Settings / Presets / Auto-downgrade
   ├─ World (merged-geometry megacity, 9 districts, hazards, jump pads, AABB solver)
   ├─ Particles / Tracers / Casings / Flash lights / Projectiles / Pickups
   ├─ Weapons (3 weapons + mod workshop + optics)
   ├─ Operator / Career / Body-cam rig
   ├─ Player (kinematics, collision, body-cam camera)
   ├─ Enemies (16 classes, AI states, telegraphs, rigs)
   ├─ HUD / Body-cam UI / Input / Touch / Menu / Game state machine
   ├─ Balance Lab (playtest harness + automated audit)
   ├─ Multiplayer Netcode 2.0 (WebRTC star topology, dual channels, rescue)
   └─ Boot
tools/              — static audit + simulation + zero-dep server
.github/workflows/  — CI
```

**Rendering strategy.** Static geometry is merged into single draw calls;
repeated decorations are pooled; the entity pools (particles, tracers,
casings, flashes, projectiles) pre-allocate at boot to avoid GC hitches.
Frame-rate-adaptive settings (`Settings.downgrade()`) soften the post-stack
automatically when the machine can't hold 35+ FPS.

**Network model.** The host's browser owns the simulation (authority),
replicates world state to clients at 20&nbsp;Hz, and receives capped inputs at
30&nbsp;Hz. Reliable chat and \`start\`/\`end\` control live on one ordered
channel; lossy position/velocity snapshots live on a separate unreliable,
unordered channel — ordered channels are never used for traffic that expires
infinities of frames later. The optional TURN relay falls back through
\`turns:\` proxies for restrictive NATs.

**Balance & pacing.** Difficulty follows a saw curve: unlock waves teach new
classes one at a time, boss waves every 5th add an elite commander, squad
size scales budget without exploding damage (squad HP +10% per body, +28%
wave bodies, capped).

## Deployment

Deploy the repository root as a static site (GitHub Pages, Netlify, Vercel,
Cloudflare Pages). No build step and no runtime backend are required for the
serverless path — WebRTC invite codes carry the signaling.

## Credits

| | |
| --- | --- |
| RENDERER | Three.js r128 (WebGL) |
| POST FX | Hand-written GLSL: bloom, lens, grain, FXAA |
| AUDIO | 100% synthesized Web Audio — zero samples |
| ART | Procedural canvas textures + primitives |
| PHYSICS | Custom swept-AABB collision + analytic raycasts |
| NETWORK | WebRTC Netcode 2.0 · dual encrypted channels · multi-STUN · optional TURN |
| PLATFORMS | Desktop · phone · iPad · safe-area-aware multi-touch |
| ASSETS | None. Everything is generated at runtime. |

## License

This repository is a self-contained playground: single-file source, no bundled
license. All rights reserved by the repository owner unless otherwise stated.
No warranty; fork, adapt and play freely.
