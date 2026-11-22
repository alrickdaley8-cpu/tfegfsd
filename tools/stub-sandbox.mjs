/**
 * stub-sandbox.mjs — shared stub-DOM builders for the zero-dependency
 * runtime checks (headless-sim.mjs, mp-net-sim.mjs).
 *
 * These take the REAL game code — the vendored three.min.js + the inline
 * script — and run it in a vm context with a stubbed DOM and no-op WebGL
 * renderer. No browser binary is needed.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const TOOLROOT = dirname(fileURLToPath(import.meta.url));
export const ROOT = join(TOOLROOT, "..");

export function extractInlineScript(root = ROOT) {
  const html = readFileSync(join(root, "index.html"), "utf8");
  const scripts = [...html.matchAll(/<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  const inline = scripts.find((s) => s.includes("NEON BREACH") && s.includes("boot"));
  if (!inline) throw new Error("no inline game script found");
  return inline;
}

export function makeCtx2d() {
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

export function makeElement(tag = "div", id = "", doc = null, depth = 0) {
  const classes = new Set();
  const el = {
    tagName: (tag || "div").toUpperCase(),
    id,
    children: null,
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
    firstElementChild: null, lastElementChild: null,
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
    scrollTop: 0, scrollHeight: 1,
    parentNode: null,
    appendChild: (c) => { (el.children = el.children || []).push(c); c.parentNode = el; return c; },
    removeChild: (c) => { el.children = el.children.filter((x) => x !== c); c.parentNode = null; return c; },
    remove: () => {},
    insertBefore: (c, ref) => { el.children.unshift(c); return c; },
    append: (...cs) => cs.forEach((c) => el.appendChild(c)),
    prepend: (...cs) => cs.forEach((c) => el.children.unshift(c)),
    querySelector: () => makeElement("div", "qsel", doc, depth + 1),
    querySelectorAll: () => [],
    closest: () => null,
    getBoundingClientRect: () => ({ x: 0, y: 0, top: 0, left: 0, right: 800, bottom: 600, width: 800, height: 600 }),
    getClientRects: () => [],
    _listeners: {},
    addEventListener(type, fn) { (this._listeners[type] = this._listeners[type] || []).push(fn); },
    removeEventListener(type, fn) { const l = this._listeners[type]; if (l) this._listeners[type] = l.filter((f) => f !== fn); },
    dispatchEvent: (e) => true,
    click() { const l = this._listeners.click || []; for (const fn of [...l]) fn(); },
    submit() { (this._listeners.submit || []).forEach((fn) => fn()); },
    focus: () => {}, blur: () => {},
    setAttribute: () => {}, getAttribute: () => null, removeAttribute: () => {},
    hasAttribute: () => false, select: () => {}, setSelectionRange: () => {},
    play: () => {}, pause: () => {}, load: () => {},
    insertAdjacentHTML: () => {},
    set outerHTML(v) {}, get outerHTML() { return ""; },
    set textContent(v) { this._text = v; }, get textContent() { return this._text || ""; },
    set innerHTML(v) { this._html = v; this.children = []; }, get innerHTML() { return this._html || ""; },
  };
  // DOM-tree conveniences (elements exist, tree is empty so nested access never throws)
  if (depth < 1) {
    el.firstElementChild = makeElement("b", "firstChild", doc, depth + 1);
    el.lastElementChild = el.firstElementChild;
    el.children = Array.from({ length: 4 }, (_, i) => makeElement("div", "child" + i, doc, depth + 1));
  } else {
    el.firstElementChild = null;
    el.lastElementChild = null;
    el.children = [];
  }
  el.parentElement = null;
  Object.defineProperty(el, "className", {
    get() { return [...classes].join(" "); },
    set(v) { classes.clear(); if (v) String(v).split(/\s+/).forEach((c) => classes.add(c)); },
  });
  return el;
}

export function makeCanvas(doc) {
  const el = makeElement("canvas", "canvas", doc, 0);
  el.width = 800; el.height = 600;
  el.getContext = (kind) => {
    if (kind === "2d") return makeCtx2d();
    return null; // no WebGL — the game's renderer is replaced by the harness
  };
  return el;
}

export function createDocumentStub(makeEl = makeElement) {
  const doc = {
    readyState: "complete",
    hidden: false,
    visibilityState: "visible",
    pointerLockElement: null,
    activeElement: null,
    _byId: new Map(),
    createElement(tag) {
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
    querySelector: () => makeElement("div", "qsel", doc, 1),
    querySelectorAll: () => [],
    addEventListener: () => {}, removeEventListener: () => {},
    exitPointerLock: () => {},
    execCommand: () => true,
    body: null,
    documentElement: null,
  };
  doc.documentElement = makeElement("html", "documentElement", doc);
  doc.body = makeElement("body", "body", doc);
  return doc;
}

export function createStorage() {
  const storage = new Map();
  return {
    getItem: (k) => (storage.has(String(k)) ? storage.get(String(k)) : null),
    setItem: (k, v) => { storage.set(String(k), String(v)); },
    removeItem: (k) => { storage.delete(String(k)); },
    clear: () => storage.clear(),
    key: (i) => [...storage.keys()][i] ?? null,
    get length() { return storage.size; },
  };
}

export function audioParam(v) {
  return {
    value: v,
    setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {},
    setTargetAtTime() {}, cancelScheduledValues() {}, cancelAndHoldAtTimeAndPropagate() {},
  };
}

export function makeFakeAudio() {
  return class FakeAudioContext {
    constructor() {
      this.sampleRate = 48000; this.currentTime = 0; this.state = "running";
      this.destination = {};
    }
    createOscillator() {
      return {
        type: "sine", frequency: audioParam(440),
        detune: audioParam(0),
        connect() {}, start() {}, stop() {}, disconnect() {},
      };
    }
    createGain() {
      return { gain: audioParam(1), connect() {}, disconnect() {} };
    }
    createBuffer(ch, len, rate) {
      return { numberOfChannels: ch, length: len, sampleRate: rate, getChannelData: () => new Float32Array(len) };
    }
    createBufferSource() {
      return { buffer: null, connect() {}, start() {}, stop() {}, disconnect() {} };
    }
    createBiquadFilter() {
      return {
        type: "lowpass", frequency: audioParam(350),
        Q: audioParam(1), gain: audioParam(1),
        connect() {}, disconnect() {},
      };
    }
    createDynamicsCompressor() {
      return { connect() {}, disconnect() {}, threshold: audioParam(-24), knee: audioParam(30), ratio: audioParam(12), attack: audioParam(0.003), release: audioParam(0.25) };
    }
    createAnalyser() {
      return { connect() {}, disconnect() {}, fftSize: 256, frequencyBinCount: 128, getByteFrequencyData() {}, getByteTimeDomainData() {}, setValueAtTime() {} };
    }
    createStereoPanner() { return { pan: audioParam(0), connect() {}, disconnect() {} }; }
    createScriptProcessor() { return { connect() {}, disconnect() {}, onaudioprocess: null, bufferSize: 256 }; }
    resume() { this.state = "running"; return Promise.resolve(); }
    suspend() { return Promise.resolve(); }
  };
}

export function makeRendererPatch() {
  return `
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
}

/**
 * Build a vm sandbox with stub DOM + audio + capture-only rAF.
 * `extras` lets mp-net-sim inject RTCPeerConnection (and a clock hook).
 */
export function buildGameSandbox({ label = "sandbox", threePath = null, extras = {} } = {}) {
  const document = createDocumentStub();
  const localStorage = createStorage();
  const rAF = { pending: null, count: 0 };
  let nowMs = 0;

  const sandbox = {
    console,
    document,
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
    requestAnimationFrame: (cb) => { rAF.pending = cb; rAF.count++; return 1; },
    cancelAnimationFrame: () => {},
    setTimeout, clearTimeout, setInterval, clearInterval,
    AudioContext: makeFakeAudio(),
    webkitAudioContext: makeFakeAudio(),
    // web-standard globals the game relies on for the compressed invite/answer codes
    btoa: (s) => Buffer.from(String(s), "binary").toString("base64"),
    atob: (s) => Buffer.from(String(s), "base64").toString("binary"),
    escape: (s) => Buffer.from(String(s), "utf8").toString("binary"),
    unescape: (s) => Buffer.from(String(s), "binary").toString("utf8"),
    Blob,
    CompressionStream,
    DecompressionStream,
    Response,
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => true,
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.top = sandbox;
  sandbox.frames = { length: 1, self: sandbox };
  Object.assign(sandbox, extras || {});

  return {
    sandbox,
    document,
    localStorage,
    rAF,
    advanceNow: (ms) => { nowMs += ms; return nowMs; },
    setNow: (ms) => { nowMs = ms; },
    pumpOneFrame() {
      if (!rAF.pending) return false;
      const cb = rAF.pending; rAF.pending = null;
      nowMs += 1000 / 60;
      cb();
      return true;
    },
    getNowMs: () => nowMs,
  };
}
