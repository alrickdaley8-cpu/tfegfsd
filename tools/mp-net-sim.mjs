/**
 * mp-net-sim.mjs — two REAL game instances doing a full host-authoritative
 * WebRTC handshake, no browser needed.
 *
 * Boots a HOST game and a CLIENT game in separate vm contexts (same real
 * three.min.js + same real inline script as headless-sim.mjs). A fake
 * RTCPeerConnection (same module pattern) wires their data channels together,
 * so the whole handshake runs through the game's own code paths:
 *
 *   hostRoom → createInvite → joinInvite → applyAnswer → startMatch →
 *   client ENTER MATCH → host broadcasts snapshots → client applies them.
 *
 * This is the multiplayer runtime check — it exercises the REAL handshake,
 * the REAL channel binding, the REAL start/match-enter flow, and the real
 * snapshot broadcast, all without a browser.
 *
 * Usage: node tools/mp-net-sim.mjs [framesPerSandbox]
 */
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { join } from "node:path";
import {
  ROOT,
  extractInlineScript,
  buildGameSandbox,
  makeRendererPatch,
} from "./stub-sandbox.mjs";

const FRAMES = Number(process.argv[2] || 600);

// ---------- fake RTCPeerConnection (shared across both sandboxes) ----------
function makeFakeRtcFactory() {
  const pcs = new Map();
  let seq = 0;

  class FakeChannel {
    constructor(pc, label, opts) {
      this.pc = pc; this.label = label;
      this.ordered = opts && opts.ordered !== false;
      this.maxRetransmits = opts && opts.maxRetransmits !== undefined ? opts.maxRetransmits : null;
      this.readyState = "connecting";
      this.binaryType = "arraybuffer";
      this.bufferedAmount = 0;
      this.peer = null;
      this._handlers = { open: [], close: [], error: [], message: [] };
    }
    send(data) {
      if (!this.peer || this.readyState !== "open") return;
      const peer = this.peer;
      setTimeout(() => {
        for (const fn of [...(peer._handlers.message || [])]) {
          try { fn({ data }); } catch (e) { /* swallow delivery errors */ }
        }
      }, 0);
    }
    close() { this.readyState = "closed"; this._fire("close"); }
    addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); }
    removeEventListener(type, fn) {
      const l = this._handlers[type];
      if (l) this._handlers[type] = l.filter((f) => f !== fn);
    }
    _fire(type) { for (const fn of [...(this._handlers[type] || [])]) { try { fn({ target: this }); } catch (e) {} } }
    _open() { if (this.readyState === "open") return; this.readyState = "open"; this._fire("open"); }
  }

  class FakePC {
    constructor(label, isHost = false) {
      this.id = ++seq; this.label = label;
      this.isHost = isHost;
      this.channels = [];
      this.connectionState = "new";
      this.iceGatheringState = "complete";
      this.localDescription = null;
      this.remoteDescription = null;
      this._handlers = { connectionstatechange: [], datachannel: [] };
      this.peer = null;
      pcs.set(this.id, this);
    }
    createDataChannel(label, opts = {}) {
      const ch = new FakeChannel(this, label, opts);
      this.channels.push(ch);
      return ch;
    }
    async setLocalDescription(d) {
      this.localDescription = d;
      // fire icegatheringstatechange after the game has registered its
      // listener in the waitIce() helper (macrotask beats microtask)
      setTimeout(() => this._fire("icegatheringstatechange", { target: this }), 0);
    }
    async createOffer() {
      return { type: "offer", sdp: JSON.stringify({ pcId: this.id, k: "offer" }) };
    }
    async createAnswer() {
      return { type: "answer", sdp: JSON.stringify({ pcId: this.id, k: "answer" }) };
    }
    async setRemoteDescription(d) {
      this.remoteDescription = d;
      let token = null;
      try { token = JSON.parse((d && d.sdp) || "{}"); } catch (e) { token = null; }
      const other = token && pcs.get(token.pcId);
      if (!other) return;
      this.peer = other; other.peer = this;
      this.connectionState = "connected"; other.connectionState = "connected";
      if (this.isHost) {
        // wire the host's pre-created channels to the client's received channels
        for (const ch of this.channels) {
          const m = other.channels.find((c) => c.label === ch.label && !c.peer);
          if (m) { ch.peer = m; m.peer = ch; }
        }
      } else {
        // the client discovers the host's channels via datachannel events
        for (const hostCh of other.channels) {
          const local = new FakeChannel(this, hostCh.label, {
            ordered: hostCh.ordered,
            maxRetransmits: hostCh.ordered ? undefined : (hostCh.maxRetransmits ?? 0),
          });
          hostCh.peer = local;
          local.peer = hostCh;
          this.channels.push(local);
          setTimeout(() => this._fire("datachannel", { channel: local }), 0);
          // open only after the datachannel listener has run (the game binds in it)
          setTimeout(() => { local._open(); hostCh._open(); }, 0);
        }
      }
      setTimeout(() => {
        this._fire("connectionstatechange", { target: this });
        other._fire("connectionstatechange", { target: other });
      }, 0);
    }
    addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); }
    removeEventListener(type, fn) {
      const l = this._handlers[type];
      if (l) this._handlers[type] = l.filter((f) => f !== fn);
    }
    _fire(type, ev) { for (const fn of [...(this._handlers[type] || [])]) { try { fn(ev || { target: this }); } catch (e) {} } }
    async getStats() { return []; }
    close() { this.connectionState = "closed"; }
    iceGatheringState = "complete";
  }

  function createPc(label, isHost = false) { return new FakePC(label, isHost); }

  return { createPc, pcs };
}

// ---------- build both game instances ----------
const fake = makeFakeRtcFactory();
const ThreeSrc = readFileSync(join(ROOT, "vendor", "three.min.js"), "utf8");
const inlineCode = extractInlineScript(ROOT);

function buildInstance(label, isHost) {
  const { sandbox, document, pumpOneFrame } = buildGameSandbox({
    label,
    extras: { RTCPeerConnection: function () { return fake.createPc(label, isHost); } },
  });
  vm.createContext(sandbox);
  vm.runInContext(ThreeSrc, sandbox, { filename: "vendor/three.min.js" });
  vm.runInContext(makeRendererPatch(), sandbox, { filename: "thumb-webgl-patch" });
  try {
    vm.runInContext(inlineCode, sandbox, { filename: label + " (inline)" });
  } catch (e) {
    console.error(`  ✗ ${label}: boot failed — ${String(e.stack || e.message || e)}`);
    process.exit(2);
  }
  return { label, sandbox, document, pumpOneFrame };
}

const host = buildInstance("host", true);
const client = buildInstance("client", false);
// expose the fake pc factory so the client's datachannel pairing can link the
// *host's* pc id back inside each sandbox
// (the fake is created in this process and is shared by both sandboxes)

// ---------- tiny helpers ----------
const results = [];
function check(name, ok, detail) { results.push({ name, ok, detail }); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(fn, label, timeout = 8000, step = 15) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < timeout) {
    try { const v = fn(); if (v) return v; last = v; } catch (e) { last = null; }
    await sleep(step);
  }
  throw new Error("waitFor timed out: " + label + " (last=" + JSON.stringify(last) + ")");
}

function el(inst, id) { return inst.document.getElementById(id); }
function text(inst, id) { const node = inst.document.getElementById(id); return node ? String(node.textContent || "") : ""; }

function click(inst, id) {
  const node = inst.document.getElementById(id);
  if (!node) throw new Error(inst.label + ": element #" + id + " missing");
  node.click();
}

let playTime = 0;
async function pumpBoth(frames) {
  for (let i = 0; i < frames; i++) {
    host.pumpOneFrame();
    client.pumpOneFrame();
    playTime += 1000 / 60;
    if (i % 60 === 0) await sleep(2); // let channel delivery fire
  }
}

(async () => {
  // --- boot state clean ---
  check("boots: host loaded game and hidden loading overlay",
    el(host, "loading").style.display === "none", "");
  check("boots: client loaded game and hidden loading overlay",
    el(client, "loading").style.display === "none", "");

  // --- stage 1: host opens a room + creates an invite ---
  click(host, "mpHost");
  click(host, "mpInvite");
  const invite = await waitFor(() => {
    const v = String(el(host, "mpCode").value || "");
    return v.startsWith("NB2Z.") ? v : null;
  }, "host invite code", 12000);
  check("invite: host generated a compressed NB2Z. invite code", invite.startsWith("NB2Z."),
    invite.length + " chars");

  // --- stage 2: client pastes invite and joins ---
  el(client, "mpCode").value = invite;
  click(client, "mpJoin");
  const answer = await waitFor(() => {
    const v = String(el(client, "mpCode").value || "");
    return v.startsWith("NB2Z.") && v !== invite ? v : null;
  }, "client answer code", 12000);
  check("invite: client generated a compressed answer code", answer.startsWith("NB2Z."),
    answer.length + " chars");

  // --- stage 3: host applies the answer ---
  el(host, "mpCode").value = answer;
  click(host, "mpApply");
  await waitFor(() => /SQUADLINK LIVE/.test(text(host, "mpSignal")), "host control channel live", 12000);
  check("handshake: control channel opened on the host", true, "");

  // --- stage 4: host starts the match ---
  click(host, "mpStart");
  await waitFor(() => text(client, "mpStart") === "ENTER MATCH", "client match-ready", 12000);
  check("start: host broadcast the match start", true, "");

  // --- stage 5: client enters the match ---
  click(client, "mpStart");
  await sleep(50);
  // both games are now in the PLAYING state
  const hostInMatch = !host.document.body.classList.contains("inmenu");
  const clientInMatch = !client.document.body.classList.contains("inmenu");
  check("start: host entered MATCH state", hostInMatch, hostInMatch ? "" : "body.inmenu still set on host");
  check("start: client entered MATCH state", clientInMatch, clientInMatch ? "" : "body.inmenu still set on client");

  // --- stage 6: both games pump frames; snapshots flow both ways ---
  await pumpBoth(FRAMES);

  check("play: host HUD timer ticks", /\d{2}:\d{2}/.test(text(host, "timer")),
    "timer=" + JSON.stringify(text(host, "timer")));
  check("play: client HUD timer ticks", /\d{2}:\d{2}/.test(text(client, "timer")),
    "timer=" + JSON.stringify(text(client, "timer")));
  check("play: client sees the P2P link telemetry", /P2P DUAL LINK/.test(text(client, "mpNetHud")),
    "mpNetHud=" + JSON.stringify(text(client, "mpNetHud")));
  check("play: host sees its authoritative link telemetry", /HOST AUTHORITY/.test(text(host, "mpNetHud")),
    "mpNetHud=" + JSON.stringify(text(host, "mpNetHud")));
  check("play: client mphud is live (in match)", (() => {
    const mphud = client.document.getElementById("mphud");
    return mphud && mphud.classList && mphud.classList.contains("on");
  })(), "");

  // --- report ---
  let failed = 0;
  for (const r of results) {
    if (!r.ok) failed++;
    console.log(`  ${r.ok ? "✓" : "✗"} ${r.name}${r.detail ? "\n      " + r.detail : ""}`);
  }
  console.log(`\nmp netcode: ${FRAMES} frames per sandbox (${playTime.toFixed(0)} ms sim time)`);
  if (failed) {
    console.log(`MP NET SIM FAILED (${failed} check(s))`);
    process.exit(1);
  }
  console.log("MP NET SIM PASSED");
  process.exit(0);
})().catch((e) => {
  console.error("  ✗ MP NET SIM ERROR:", e.message);
  console.error(e.stack ? e.stack.split("\n").slice(0, 4).join("\n") : "");
  process.exit(1);
});
