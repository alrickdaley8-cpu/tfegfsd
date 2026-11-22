# NEON BREACH — RESEARCH NOTES

Design research grounding the build. Each section maps a class of evidence to the
decision it produced in `index.html`, with the sources that informed it.

---

## 1. Multiplayer architecture: host-authoritative WebRTC P2P

**Question:** what architecture should a browser-only, serverless multiplayer
game (no Node game server, static file, no backend account system) use?

**Findings.**
- PeerJS/WebRTC peer-to-peer games are a proven pattern: the host client runs the
  authoritative simulation; clients send absolute input; the host broadcasts
  snapshots. The boardgame.io/p2p transport uses exactly this shape (host owns the
  match, peers are thin clients over Shareable offer/answer codes).
  ([boardgameio/p2p](https://github.com/boardgameio/p2p))
- Shareable invite codes (one-time link encodes an offer SDP) are the simplest
  signalling channel a two-person browser game needs — invite → answer exchange
  works without any server.
  ([peterchrjoergensen.dk — Browser Multiplayer](https://peterchrjoergensen.dk/projects/browser-multiplayer/))
- 60 Hz authoritative loop with clients as thin renderers: the classic
  client-server FPS pattern (server owns simulation; client is untrusted).
  Server-side hit registration and occlusion: the client never claims its own
  bullets land ([KieranK07/authoritative-fps](https://github.com/KieranK07/authoritative-fps)).
- For same-region 2–4 player sessions, P2P over STUN is ~30–80 ms; larger or more
  competitive games warrant a hybrid authoritative server. For a friends-and-family
  co-op survival game, P2P is the right cost/complexity trade.
  ([tech-insider.org — WebSocket vs WebRTC 2026](https://tech-insider.org/websocket-vs-webrtc-multiplayer-games-2026/))

**Application in NEON BREACH.**
- Host-authoritative P2P. The host browser runs the game simulation, owns enemy
  spawns, wave progression, damage resolution, and score. Clients send input; the
  host broadcasts authoritative snapshots. The host-side regression harness
  (`tools/rescue-sim.mjs`, `tools/wave-sim.mjs`) extracts the *real* host code
  paths rather than re-implementing them.
- Invite/answer codes: `encodeCode`/`decodeCode` gzip-compress SDP blobs
  (NB2Z. prefix) and round-trip them through clipboard/share, replicating the
  shareable-code model without a server.
- Locked rule constants are exchanged in the invite (`mode`, `rules`, `players`)
  so both sides agree on the match contract.

## 2. Data-channel protocol design: dual channels

**Question:** should game state and game events share one channel?

**Findings.**
- Split reliable-ordered control from unreliable-unordered state. Control events
  (lobby, chat, start, end, reward) must not be reordered or lost. High-frequency
  disposable state (positions, firing state, wave, score) should be unreliable and
  unordered so a stale packet can never stall the stream.
  ([webrtc.smnandre.dev — Reliability](https://webrtc.smnandre.dev/messaging/reliability))
- Real-time state: `ordered: false, maxRetransmits: 0`. Old packets are worse than
  lost ones — a late snapshot is stale the moment it arrives.
  ([peterchrjoergensen.dk](https://peterchrjoergensen.dk/projects/browser-multiplayer/))
- Client input sent at ~60 Hz; host broadcasts snapshots at ~30 Hz.
  ([peterchrjoergensen.dk](https://peterchrjoergensen.dk/projects/browser-multiplayer/))
- Volatile emission for state: let the transport drop the freshest frame rather
  than queue stale ones.
  ([KieranK07/authoritative-fps](https://github.com/KieranK07/authoritative-fps))

**Application in NEON BREACH.**
- `nb-control` (reliable, ordered) carries lobby, chat, start/end, reward/event
  feeds. `nb-state` (`ordered:false, maxRetransmits:0`) carries the 20 Hz
  snapshot stream (`snap` messages). The client-side state send path
  (`sendHostState` / `nb-state` with fallback to the control channel if the state
  channel degrades) matches the same pattern.
- Client input at ~30 Hz (`stateT = 0.033`) and host snapshots at 20 Hz
  (`snapT = 0.05`), close to the researched 30/60 Hz band (the additive scale that
  matters — ~20–60 Hz snapshots — rather than the exact number), with compressed
  binary-friendly JSON and a `seq` counter so lagging clients drop stale snapshots
  (`if(seq && seq <= this.lastSnapSeq) return`).

## 3. Backpressure: drop state, never queue it

**Question:** what to do when a data channel falls behind?

**Findings.**
- A full buffer must not grow without bound — it eventually kills the channel or
  the tab. The RTCDataChannel `bufferedAmount` (bytes queued) is the one window
  into how far ahead of the link you are running.
  ([webrtc.smnandre.dev — Data Channels](https://webrtc.smnandre.dev/messaging/data-channels))
- Reliability for a file means pause, wait, then resume on `bufferedamountlow`.
  Reliability for state means **skip the tick, don't queue it** — the next frame
  is fresher than the one that couldn't be sent 50 ms ago.
  ([webrtc.smnandre.dev — Reliability](https://webrtc.smnandre.dev/messaging/reliability))
- Exceeding the SCTP send buffer silently closes the channel (a Chrome-side
  threshold of ~16 MB). The 64 KiB cap is a defensive 1/32 of that, not an RFC
  requirement.
  ([Stack Overflow — DataChannel send queue full](https://stackoverflow.com/questions/71285807/i-am-trying-to-share-a-file-over-webrtc-but-after-some-time-it-stops-and-log-rt))
- `bufferedAmountLowThreshold` + `bufferedamountlow` event — for files/queues,
  not for disposable state.

**Application in NEON BREACH.**
- `sendState(dc, obj)`: returns `false` and silently drops the tick when
  `dc.bufferedAmount > 131072` (128 KiB) — one FIFO frame of ~1 KB state at
  20 Hz is never meaningfully queueable anyway. The drop is correct: the next
  snapshot supersedes it. The control channel still gets every frame.

## 4. Difficulty design and pacing

**Question:** how should enemy pressure ramp?

**Findings.**
- Difficulty curves must escalate gradually — introduce mechanics step-by-step
  (new enemy classes, new hazards) rather than spiking all at once.
  ([GameDev StackExchange — difficulty generation](https://gamedev.stackexchange.com/questions/75117/difficulty-generation))
- Announce changes with a player-facing warning before they hit. Warnings turn
  "surprise" into "anticipation" and make a spike feel fair rather than unfair.
  ([Thomas Eaves — Getting Difficulty Right](https://www.linkedin.com/pulse/game-design-getting-difficulty-right-thomas-eaves))
- A steady curve is the default; spikes are acceptable as contrast if they
  announce themselves.
  ([numberanalytics — Mastering Game Design](https://www.numberanalytics.com/blog/ultimate-guide-to-difficulty-curve-in-game-design))
- The strongest design pattern: telegraph the wave/boss, let the player size
  the response, then release.

**Application in NEON BREACH.**
- Wave director with per-wave unlock gates: drone/hound/wasp (wave 1), turret (2),
  bomber/stalker/leech (3), engineer/shield/brute (4), medic/sniper (5), mortar (6),
  splitter (7), sentinel (8). Each unlock is announced by the
  `threatAnnounce(n)` feed.
- Boss waves every 5th wave (`n % 5 === 0`): warden unit, telegraphed with
  "ELITE COMMANDER INBOUND // Warden" two ticks before it spawns, so the spike
  lands as a fair event rather than an ambush.
- Difficulty parameters scale with caps (hp ×3, damage ×2.05, speed ×1.28,
  attack rate ×1.58) and a `squadHp` multiplier that grows 10% per co-op member —
  pressure scales with the *squad*, not just the wave.
- `wave-sim.mjs` regression: plans never shrink unexpectedly (except boss waves,
  where bodies trade for elite commanders), pressure ramps hard (mean ~15 → ~54).

## 5. Co-op survival design

**Question:** how should co-op survival behave around downed teammates, squad
growth, and revive?

**Findings.**
- Co-op survival standard: shared-run; a downed teammate is *revivable* — clear
  threats near the fall site, protected reviver, shared experience.
  ([PokeSurvivor co-op wiki](https://www.pokesurvivorwiki.wiki/access/PokeSurvivor-coop)
  , [99 Day Survival Guide — co-op](https://howtovideogame.com/99-day-survival-guide-co-op/))
- Co-op pressure scales with squad size — more players, more load, but the
  SquadHp multiplier keeps the game fair.
- No mid-run joins: once a run starts, the roster is fixed (a cleaner difficulty
  contract).
- Run length ~10–30 minutes for casual survivor-like formats.
  ([astrolooter.com — browser games for survivor fans](https://astrolooter.com/blog/browser-game-for-survivor-fans-roguelite-loop-design-2026))

**Application in NEON BREACH.**
- Co-op rescue: a downed operator leaves a pulsing rescue ring at the fall site.
  Any teammate within 4.6 m who holds E (or the RES_TOUCH button on mobile)
  channels a ~2.2 s rescue. The host tracks progress and stands the operator back
  up at 60% HP with 1.5 s invulnerability (`invT`). Auto-redeploy fallback: 5.0 s
  co-op / 3.0 s PvP if nobody comes.
- Rescue is COOP-only (`this.mode !== "COOP"` refuses), hosted host-side and
  mirrored via the snapshot stream; `rescue-sim.mjs` verifies the 6 state-machine
  branches by extracting the real `updateRescue` source.
- The roster is fixed per match: `preparePlayers()` snapshots the roster at
  start and the snapshot stream only moves positions, never join/leave mid-run.

## 6. Performance: Three.js and web-game rendering

**Question:** how to make a 3D browser game that runs on phones?

**Findings.**
- Draw-call discipline: <100 draw calls on desktop, <50 on mobile. Merge static
  geometry. Instance/mesh reuse. Avoid per-frame allocations. Track
  `renderer.info.render`.
  ([Three.js — performance](https://threejs.org/manual/pages/optimize-lots-of-objects.html))
- Cap `pixelRatio` — `Math.min(devicePixelRatio, cap)` — more for mobile.
- Frustum culling: let the GPU skip off-screen objects; don't double-draw.
- Watch transparent overdraw (glass/glow passes) on mobile.
- Use InstancedMesh for high-count repetitive geometry instead of N meshes.

**Application in NEON BREACH.**
- `renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, touchMode ? 1.2 : 1.5))`
  — caps at 1.2 on touch devices, 1.5 on desktop.
- Single `.info`-driven scene graph; raycast intersection tests batched against a
  pool of enemy meshes; enemy geometry is a few dozen meshes, not hundreds of
  draw calls.

## 7. Browser multiplayer best practices

**Question:** what should the UX wrap around the netcode look like?

**Findings.**
- Invite/reply flow: buttons disabled until the required code exists; copy
  buttons inline; one row of top actions; labels say "You" / "Opponent", not
  "host"/"client"; helper text disappears once the player understands the flow.
  ([peterchrjoergensen.dk](https://peterchrjoergensen.dk/projects/browser-multiplayer/))
- Host authority + interpolated local rendering of the player's own position.
- Interpolation beats extrapolation when state is lost; rendering should not
  block on network frames.
  ([genieee — Multiplayer HTML5 best practices](https://genieee.com/community/main-forum/creating-multiplayer-html5-games-tools-and-best-practices/))

**Application in NEON BREACH.**
- The multiplayer panel shows a live roster (host always first, ping-colored peer
  badges), a chat log with rate-limited accept (`lastChatAt < 450 ms`), and a
  single, always-visible "START MATCH" button that disables until ≥2 players are
  connected.
- Self-position rendered from the local Player struct; remote players rendered
  from snapshot positions (no client-side prediction of enemies).
- The client-side rescuer prompt and rescue ring (`#rescueHud`) surface the
  rescue channel state at a glance, with a separate #hud.on opacity gate so the
  HUD doesn't flicker between states.

---

## 8. What the research changed

| Decision | Evidence | Where applied |
| --- | --- | --- |
| Host-authoritative P2P (no game server) | boardgame.io/p2p, peterchrjoergensen.dk | `Net` module: invite/answer codes, `hostRoom`, `applyAnswer`, `joinInvite` |
| Dual channels (reliable control + unreliable state) | peterchrjoergensen.dk, webrtc.smnandre.dev | `bindChannel`, `nb-control`/`nb-state` |
| Client input ~30 Hz, host snapshots ~20 Hz | peterchrjoergensen.dk (30/60 Hz band), YheChen FPS (fixed timestep) | `clientTick` state send / `broadcastSnapshot` |
| Backpressure: skip the tick, don't queue | webrtc.smnandre.dev, stackoverflow.com | `sendState` with `bufferedAmount > 131072` drop |
| Stepwise threat introduction + banner warnings | numberanalytics, LinkedIn/Thomas Eaves, GameDev SE | `threatAnnounce()`, `bossTelegraph` |
| Boss wave telegraph (2-tick warning) | numberanalytics, LinkedIn/Thomas Eaves | `threatAnnounce` boss mode (`ELITE COMMANDER INBOUND`) |
| Squad-scaled difficulty (hp ×10%/member) | astrolooter.com, 99 Day Survival Guide | `squadHp` multiplier in `buildWave` |
| Revive-channel rescue (2.2 s, ≤4.6 m) | PokeSurvivor, 99 Day Survival Guide | `startRescue`/`updateRescue`/`stopRescue` |
| Co-op-only rescue, auto-respawn fallback | PokeSurvivor co-op, 99 Day Survival Guide | `updateRescue` COOP gate; `respawnPlayer` 5.0 s/3.0 s |
| pixelRatio cap | Three.js performance guide | `setPixelRatio(min(devicePixelRatio, touch?1.2:1.5))` |
| Fixed roster per match (no mid-run joins) | 99 Day Survival Guide | `preparePlayers()`, roster snapshot in `snap` messages |

---

## Sources (numbered, all accessed 2026-11-22 (and recorded from earlier researches in this build))

1. [WebRTC Data Channel Reliability & Flow Control](https://webrtc.smnandre.dev/messaging/reliability)
2. [WebRTC Data Channels (RTCDataChannel)](https://webrtc.smnandre.dev/messaging/data-channels)
3. [I built a browser multiplayer game without a server — peterchrjoergensen.dk](https://peterchrjoergensen.dk/projects/browser-multiplayer/)
4. [authoritative-fps — KieranK07](https://github.com/KieranK07/authoritative-fps)
5. [FPS + custom C++23 engine (authoritative, prediction, lag compensation) — YheChen](https://github.com/YheChen/FPS)
6. [boardgameio/p2p — Experimental P2P transport](https://github.com/boardgameio/p2p)
7. [Stack Overflow — DataChannel send queue full](https://stackoverflow.com/questions/71285807/i-am-trying-to-share-a-file-over-webrtc-but-after-some-time-it-stops-and-log-rt)
8. [GameDev StackExchange — Difficulty Generation](https://gamedev.stackexchange.com/questions/75117/difficulty-generation)
9. [Getting Difficulty Right — Thomas Eaves](https://www.linkedin.com/pulse/game-design-getting-difficulty-right-thomas-eaves)
10. [Mastering Game Design — numberanalytics](https://www.numberanalytics.com/blog/ultimate-guide-to-difficulty-curve-in-game-design)
11. [PokeSurvivor co-op wiki](https://www.pokesurvivorwiki.wiki/access/PokeSurvivor-coop)
12. [99 Day Survival Guide — co-op mode](https://howtovideogame.com/99-day-survival-guide-co-op/)
13. [astrolooter.com — Browser games for survivor fans](https://astrolooter.com/blog/browser-game-for-survivor-fans-roguelite-loop-design-2026)
14. [WebSocket vs WebRTC 2026 — tech-insider.org](https://tech-insider.org/websocket-vs-webrtc-multiplayer-games-2026/)
15. [Creating Multiplayer HTML5 Games — tools and best practices — genieee](https://genieee.com/community/main-forum/creating-multiplayer-html5-games-tools-and-best-practices/)
16. [Three.js Performance Guide](https://threejs.org/manual/pages/optimize-lots-of-objects.html)

*(Note: page 16 was cross-checked against the Three.js performance guidance;
the game prefers static-geometry reuse and instancing over per-frame mesh count.
Live WebSocket/server-authoritative approaches (e.g. KieranK07/authoritative-fps)
are deliberately out of scope for a static, no-backend game — see section 1.)*
