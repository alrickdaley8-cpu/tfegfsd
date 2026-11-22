/**
 * headless-sim.mjs — zero-dependency runtime boot check (own-method runtime
 * verification). Boots the REAL game — real vendored three.min.js + real
 * inline script + real frame loop — inside a vm sandbox with a stub DOM and a
 * no-op WebGL renderer. No browser binary is needed.
 *
 * It proves the game boots, wires up, and survives N pumped frames without
 * throwing: boot success, loading overlay hidden, menu-state machinery ran,
 * and the game loop re-schedules every frame.
 *
 * What it does NOT prove: real GL rendering, pointer lock, audio, or WebRTC.
 * Those still need a real browser — this is the strongest verification that
 * exists without one.
 *
 * Boots the REAL game (real vendored three.min.js + real inline script) in a
 * vm sandbox with a stub DOM and a no-op WebGL renderer. This is the
 * "own method" for runtime verification in a sandbox with no browser: it
 * executes the same code the browser would run, catching boot-time crashes,
 * dead references, and frame-loop exceptions that static checks cannot.
 *
 * NOT a replacement for a real browser — it cannot render or validate WebGL.
 * It proves the game boots, wires up, and survives N frames without throwing.
 *
 * Usage: node tools/headless-sim.mjs [frames]
 */
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, "..");

function extractInlineScript() {
  const html = readFileSync(join(ROOT, "index.html"), "utf8");
  const scripts = [...html.matchAll(/<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  const inline = scripts.find((s) => s.includes("NEON BREACH") && s.includes("boot"));
  if (!inline) throw new Error("no inline game script found");
  return inline;
}

function makeCtx2d() {
  // 2D context stub for procedural textures (fills, gradients, arcs).
  const grad = { addColorStop() {} };
  return {
    canvas: null,
    fillStyle: "#000000", strokeStyle: "#000000", lineWidth: 1,
    globalAlpha: 1, globalCompositeOperation: "source-over",
    imageSmoothingEnabled: false, imageSmoothingQuality: "low",
    font: "10px", textAlign: "left", textBaseline: "alphabetic",
    lineCap: "butt", lineJoin: "miter", miterLimit: 10, shadowBlur: 0, shadowColor: "transparent",
    clearRect() {}, fillRect() {}, strokeRect() {}, beginPath() {}, closePath() {},
    moveTo() {}, lineTo() {}, arc() {}, arcTo() {}, ellipse() {}, rect() {},
    fill() {}, stroke() {}, clip() {}, save() {}, restore() {},
    translate() {}, rotate() {}, scale() {}, transform() {}, setTransform() {},
    drawImage() {}, fillText() {}, strokeText() {}, measureText: () => ({ width: 0 }),
    createLinearGradient: () => grad, createRadialGradient: () => grad,
    createPattern: () => ({}),
    putImageData() {}, getImageData: (x, y, w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) }),
    createImageData: (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) }),
    setLineDash() {}, getLineDash: () => [],
  };
}

function makeElement(tag = "div", id = "", doc = null) {
  const classes = new Set();
  const el = {
    tagName: (tag || "div").toUpperCase(),
    id,
    children: [],
    style: new Proxy({ width: "", height: "", display: "", color: "" }, {
      set(t, k, v) { t[k] = v; return true; },
      get(t, k) {
        if (k === "setProperty") return (name, value) => { t[name] = value; };
        if (k === "getPropertyValue") return (name) => t[name] || "";
        if (k === "removeProperty") return (name) => { delete t[name]; };
        if (k === "cssText") return t[k] || "";
        if (k === "transition") return t[k] || "";
        if (k === "MozTransform" || k === "msTransform" || k === "WebkitTransform") return t[k] || "";
        return t[k] !== undefined ? t[k] : "";
      },
    }),
    classList: {
      toggle: (c, force) => { const on = force === undefined ? !classes.has(c) : force; on ? classes.add(c) : classes.delete(c); return on; },
      add: (...cs) => cs.forEach((c) => classes.add(c)),
      remove: (...cs) => cs.forEach((c) => classes.delete(c)),
      contains: (c) => classes.has(c),
      replace: (a, b) => { classes.delete(a); classes.add(b); },
    },
    dataset: {},
    textContent: "",
    innerHTML: "",
    value: "",
    checked: false,
    selected: false,
    disabled: false,
    open: false,
    hidden: false,
    placeholder: "",
    title: "",
    className: "",
    offsetWidth: 800, offsetHeight: 600, clientWidth: 800, clientHeight: 600,
    scrollTop: 0, scrollHeight: 1, clientHeight: 600,
    parentNode: null,
    classListContains: (c) => classes.has(c),
    appendChild: (c) => { (el.children = el.children || []).push(c); c.parentNode = el; return c; },
    removeChild: (c) => { el.children = el.children.filter((x) => x !== c); c.parentNode = null; return c; },
    remove: () => {},
    insertBefore: (c, ref) => { el.children.unshift(c); return c; },
    append: (...cs) => cs.forEach((c) => el.appendChild(c)),
    prepend: (...cs) => cs.forEach((c) => el.children.unshift(c)),
    querySelector: () => makeElement("div", "qsel", doc),
    querySelectorAll: () => [],
    closest: () => null,
    getBoundingClientRect: () => ({ x: 0, y: 0, top: 0, left: 0, right: 800, bottom: 600, width: 800, height: 600 }),
    getClientRects: () => [],
    addEventListener: () => {}, removeEventListener: () => {},
    dispatchEvent: (e) => true,
    focus: () => {}, blur: () => {},
    setAttribute: () => {}, getAttribute: () => null, removeAttribute: () => {},
    hasAttribute: () => false,
    click: () => {},
    play: () => {}, pause: () => {}, load: () => {},
    insertAdjacentHTML: () => {},
    closest: () => null,
    set outerHTML(v) {}, get outerHTML() { return ""; },
    set textContent(v) { this._text = v; }, get textContent() { return this._text || ""; },
    set innerHTML(v) { this._html = v; this.children = []; }, get innerHTML() { return this._html || ""; },
  };
  Object.defineProperty(el, "className", {
    get() { return [...classes].join(" "); },
    set(v) { classes.clear(); if (v) String(v).split(/\s+/).forEach((c) => classes.add(c)); },
  });
  return el;
}

function makeCanvas(doc) {
  const el = makeElement("canvas", "canvas", doc);
  el.width = 800; el.height = 600;
  el.getContext = (kind) => {
    if (kind === "2d") return makeCtx2d();
    return null; // no WebGL — the game's renderer is replaced by the harness
  };
  return el;
}

// ---------- build the sandbox ----------
const doc = {
  readyState: "complete",
  hidden: false,
  visibilityState: "visible",
  pointerLockElement: null,
  activeElement: null,
  documentElement: makeElement("html", "documentElement"),
  body: makeElement("body", "body"),
  _byId: new Map(),
  _byIdAndTag: new Map(),
  createElement: (tag) => {
    if (tag === "canvas") return makeCanvas(doc);
    return makeElement(tag, "", doc);
  },
  createElementNS: (ns, tag) => makeElement(tag, "", doc),
  createTextNode: (t) => ({ nodeType: 3, textContent: String(t) }),
  createDocumentFragment: () => makeElement("#fragment", "", doc),
  getElementById(id) {
    const key = String(id);
    if (!doc._byId.has(key)) doc._byId.set(key, makeElement("div", key, doc));
    return doc._byId.get(key);
  },
  querySelector: () => makeElement("div", "qsel", doc),
  querySelectorAll: () => [],
  addEventListener: () => {}, removeEventListener: () => {},
  exitPointerLock: () => {},
  execCommand: () => true,
  body: null, // replaced below (needs body defined first)
};
doc.body = makeElement("body", "body");
doc.documentElement = makeElement("html", "documentElement");
doc.body.parentNode = null;

const storage = new Map();
const localStorage = {
  getItem: (k) => (storage.has(String(k)) ? storage.get(String(k)) : null),
  setItem: (k, v) => { storage.set(String(k), String(v)); },
  removeItem: (k) => { storage.delete(String(k)); },
  clear: () => storage.clear(),
  key: (i) => [...storage.keys()][i] ?? null,
  get length() { return storage.size; },
};

const listeners = {};
const windowEvents = {
  addEventListener: (type, fn) => { (listeners[type] = listeners[type] || []).push(fn); },
  removeEventListener: () => {},
  dispatchEvent: (ev) => true,
};

const rAF = { callbacks: [], pending: null };
let nowMs = 0;

function makeAudio() {
  return class FakeAudioContext {
    constructor() {
      this.sampleRate = 48000; this.currentTime = 0; this.state = "running";
      this.destination = {};
    }
    createOscillator() {
      return {
        type: "sine", frequency: { value: 440, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} },
        detune: { value: 0, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} },
        connect() {}, start() {}, stop() {}, disconnect() {},
      };
    }
    createGain() {
      const g = { gain: { value: 1, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {}, disconnect() {} };
      return g;
    }
    createBuffer(ch, len, rate) {
      return { numberOfChannels: ch, length: len, sampleRate: rate, getChannelData: () => new Float32Array(len) };
    }
    createBufferSource() {
      return { buffer: null, connect() {}, start() {}, stop() {}, disconnect() {} };
    }
    createBiquadFilter() {
      return {
        type: "lowpass", frequency: { value: 350, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} },
        Q: { value: 1, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} },
        gain: { value: 1, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} },
        connect() {}, disconnect() {},
      };
    }
    createDynamicsCompressor() {
      return { connect() {}, disconnect() {}, threshold: { value: -24 }, knee: { value: 30 }, ratio: { value: 12 }, attack: { value: 0.003 }, release: { value: 0.25 } };
    }
    createAnalyser() {
      return { connect() {}, disconnect() {}, fftSize: 256, frequencyBinCount: 128, getByteFrequencyData() {}, getByteTimeDomainData() {}, setValueAtTime() {} };
    }
    createStereoPanner() { return { pan: { value: 0, setValueAtTime() {}, linearRampToValueAtTime() {} }, connect() {}, disconnect() {} }; }
    createScriptProcessor() { return { connect() {}, disconnect() {}, onaudioprocess: null, bufferSize: 256 }; }
    resume() { this.state = "running"; return Promise.resolve(); }
    suspend() { return Promise.resolve(); }
  };
}

const sandbox = {
  console,
  document: doc,
  // the window object is also the global
  localStorage,
  performance: { now: () => nowMs, timeOrigin: 0 },
  navigator: {
    maxTouchPoints: 0,
    onLine: true,
    language: "en-US",
    platform: "headless",
    userAgent: "NEON-BREACH-HEADLESS/1.0",
    clipboard: { writeText: async () => {}, readText: async () => "" },
    mediaDevices: undefined,
    xr: undefined,
    share: undefined,
  },
  location: { href: "https://neon-breach.local/", protocol: "https:", host: "neon-breach.local", search: "", hash: "", origin: "https://neon-breach.local" },
  history: { pushState: () => {}, replaceState: () => {}, go: () => {}, back: () => {} },
  matchMedia: (q) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {} }),
  visualViewport: { width: 1280, height: 720, offsetLeft: 0, offsetTop: 0, scale: 1, addEventListener: () => {}, removeEventListener: () => {} },
  innerWidth: 1280, innerHeight: 720, devicePixelRatio: 1,
  outerWidth: 1280, outerHeight: 720,
  requestAnimationFrame: (cb) => { rAF.pending = cb; return 1; },
  cancelAnimationFrame: () => {},
  setTimeout, clearTimeout, setInterval, clearInterval,
  AudioContext: makeAudio(),
  webkitAudioContext: makeAudio(),
  ...windowEvents,
};
sandbox.window = windowEvents.globalThis = sandbox;
sandbox.self = sandbox;
sandbox.globalThis = sandbox;
sandbox.top = sandbox;
sandbox.frames = { length: 1, self: sandbox };

const code = extractInlineScript();
const threenjs = readFileSync(join(ROOT, "vendor", "three.min.js"), "utf8");

// Fake WebGL renderer — the game only calls these, never real GL.
const FakeRenderer = `
function FakeWebGLRenderer(opts) {
  const canvas = (opts && opts.canvas) || document.createElement("canvas");
  canvas.width = 0; canvas.height = 0; canvas.style = (canvas.style || {});
  let width = opts && (opts.width || 800); let height = opts && (opts.height || 600);
  this.domElement = canvas;
  this.outputEncoding = 0;
  this.physicallyCorrectLights = false;
  this.autoClear = true;
  this.shadowMap = { enabled: false, needsUpdate: false, autoUpdate: true, type: 0 };
  this.capabilities = { maxTextureSize: 8192, maxAnisotropy: 16, isWebGL2: false };
  this.info = { render: { calls: 0, triangles: 0, points: 0, lines: 0 }, memory: { geometries: 0, textures: 0 } };
  this.xr = { enabled: false, requestAnimationFrame() {}, render() {} };
  this.setPixelRatio = function(v) {};
  this.setSize = function(w, h) { width = w; height = h; canvas.width = w; canvas.height = h; };
  this.render = function() {};
  this.renderList = function() {};
  this.clear = function() {};
  this.clearColor = function() {};
  this.setAnimationLoop = function() {};
  this.compile = function() {};
  this.resetShaders = function() {};
  this.capabilities.maxTextureSize = 8192;
  this.physicallyCorrectLights = false;
  // expose internal-ish state the game reads
  Object.defineProperty(this, "_size", { get: () => ({ width: width, height: height }) });
  Object.defineProperty(this, "_width", { get: () => width });
  Object.defineProperty(this, "_height", { get: () => height });
}
`;
// Hide pending events: no pointer lock needed for headless mode
vm.createContext(sandbox);
vm.runInContext(threenjs, sandbox, { filename: "vendor/three.min.js" });

// Patch the real THREE.WebGLRenderer with the fake (real math stays real).
const THREEPatch = `
;(function(){
  const T = window.THREE || THREE;
  if (!T) throw new Error("THREE failed to load in sandbox");
  const noop = function() {};
  T.WebGLRenderer = function FakeWebGLRenderer(opts) {
    const canvas = (opts && opts.canvas) || document.createElement("canvas");
    canvas.width = 0; canvas.height = 0;
    const self = {
      domElement: canvas,
      outputEncoding: 0, physicallyCorrectLights: false,
      autoClear: true, autoClearColor: true,
      shadowMap: { enabled: false, needsUpdate: false, autoUpdate: true, type: 0 },
      capabilities: { maxTextureSize: 8192, maxAnisotropy: 16, isWebGL2: false },
      info: { reset: noop, render: { calls: 0, triangles: 0, points: 0, lines: 0 }, memory: { geometries: 0, textures: 0 } },
      xr: { enabled: false, requestAnimationFrame: noop, render: noop, setReferenceSpaceType: noop },
      setPixelRatio: noop,
      setSize: function(w, h) { canvas.width = w; canvas.height = h; },
      render: noop, renderList: noop, clear: noop, clearColor: noop,
      setAnimationLoop: noop, compile: noop, resetShaders: noop,
      sortObjects: noop, setClearColor: noop, setRenderTarget: noop,
      setScissor: noop, setScissorTest: noop,
      outputColorSpace: 0, toneMapping: 0, toneMappingExposure: 1,
    };
    return new Proxy(self, {
      get: function(t, k) {
        if (k in t) return t[k];
        if (k === Symbol.toPrimitive) return function() { return 0; };
        return noop;
      },
      set: function(t, k, v) { t[k] = v; return true; },
    });
  };
})();
`;
vm.runInContext(THREEPatch, sandbox, { filename: "thumb-webgl-patch" });

const errors = [];
let bootOk = false;
try {
  // run with a hook so any async boot error is captured instead of crashing the process
  vm.runInContext(code, sandbox, { filename: "index.html (inline)" });
  bootOk = true;
} catch (e) {
  errors.push("BOOT: " + (e.stack || e.message || String(e)));
}

const failures = [];
const loading = sandbox.document.getElementById("loading");
const loadingHidden = loading && loading.style && loading.style.display === "none";
const body = sandbox.document.body;

// ---------------- frame pump ----------------
const FRAMES = Number(process.argv[2] || 240);
let framesRun = 0, lastError = null;
const startMs = Date.now();
for (let i = 0; i < FRAMES; i++) {
  nowMs += 1000 / 60;
  if (!rAF.pending) break;
  const cb = rAF.pending; rAF.pending = null;
  try {
    cb();
    framesRun++;
  } catch (e) {
    lastError = e;
    break;
  }
}
const elapsed = ((Date.now() - startMs) / 1000).toFixed(2);

// ---------------- report ----------------
let results = [];
function check(name, ok, detail) { results.push({ name, ok, detail }); }

check("boot: no exception during inline script eval", bootOk, errors[0] || "");
check("boot: loading overlay hidden", !!loadingHidden, loadingHidden ? "" : "loading.style.display=" + (loading && loading.style && loading.style.display));
check("boot: requestAnimationFrame scheduled", rAF.pending !== null || framesRun > 0, "pending=" + !!rAF.pending);

if (errors.length) {
  check("boot: details", false, errors[0].slice(0, 600));
}
if (lastError) {
  check("frames: no exception across " + framesRun + " pumped frames", false, lastError.stack ? lastError.stack.split("\n").slice(0, 3).join(" | ") : String(lastError));
} else {
  check("frames: no exception across " + framesRun + " pumped frames", true, "");
}

// the menu-state machinery ran: the game toggled its body/inmenu class every frame
const inMenuPersisted = body && body.classList && body.classList.contains("inmenu");
check("frames: menu-state path exercised (body.inmenu set)", !!inMenuPersisted,
  inMenuPersisted ? "" : "body classes not set after " + framesRun + " frames");

// the loop re-registered itself: the game is alive for the next frame
check("frames: loop re-scheduled (requestAnimationFrame pending)", rAF.pending !== null,
  rAF.pending ? "" : "game loop did not re-schedule");

let failed = 0;
for (const r of results) {
  const mark = r.ok ? "✓" : "✗";
  if (!r.ok) failed++;
  console.log(`  ${mark} ${r.name}${r.detail ? "\n      " + r.detail : ""}`);
}
console.log(`\nheadless boot: ${framesRun}/${FRAMES} frames in ${elapsed}s (${bootOk ? "boot ok" : "BOOT FAILED"})`);
if (failed) {
  console.log(`HEADLESS SIM FAILED (${failed} check(s))`);
  process.exit(1);
}
console.log("HEADLESS SIM PASSED");
process.exit(0);
