/**
 * headless-sim.mjs — zero-dependency runtime boot check.
 *
 * Boots the REAL game (real vendored three.min.js + real inline script) in a
 * vm sandbox with a stub DOM and a no-op WebGL renderer, clicks the play
 * button, and pumps N gameplay frames. This is the "own method" for runtime
 * verification in a sandbox with no browser: it executes the same code the
 * browser would run.
 *
 * NOT a replacement for a real browser — it cannot render or validate WebGL.
 *
 * Usage: node tools/headless-sim.mjs [frames]
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

const { sandbox, rAF, pumpOneFrame, advanceNow, getNowMs } =
  buildGameSandbox({ label: "headless-solo", threePath: join(ROOT, "vendor", "three.min.js") });
const THREE = readFileSync(join(ROOT, "vendor", "three.min.js"), "utf8");

vm.createContext(sandbox);
vm.runInContext(THREE, sandbox, { filename: "vendor/three.min.js" });
vm.runInContext(makeRendererPatch(), sandbox, { filename: "thumb-webgl-patch" });

const code = extractInlineScript(ROOT);
const errors = [];
let bootOk = false;
try {
  vm.runInContext(code, sandbox, { filename: "index.html (inline)" });
  bootOk = true;
} catch (e) {
  errors.push("BOOT: " + (e.stack || e.message || String(e)));
}

const failures = [];
const loading = sandbox.document.getElementById("loading");
const loadingHidden = loading && loading.style && loading.style.display === "none";
const body = sandbox.document.body;

let framesRun = 0, lastError = null;
const startMs = Date.now();

// start a real run: click the play button. Anything the game needs for play
// is exercised right here.
let playClicked = false;
let playError = null;
try {
  const playBtn = sandbox.document.getElementById("playBtn");
  if (playBtn) { playBtn.click(); playClicked = true; }
} catch (e) {
  playError = e;
}

for (let i = 0; i < FRAMES; i++) {
  advanceNow(1000 / 60);
  if (!rAF.pending) break;
  try {
    pumpOneFrame();
    framesRun++;
  } catch (e) {
    lastError = e;
    break;
  }
}
const elapsed = ((Date.now() - startMs) / 1000).toFixed(2);

let results = [];
function check(name, ok, detail) { results.push({ name, ok, detail }); }

check("boot: no exception during inline script eval", bootOk, errors[0] || "");
check("boot: loading overlay hidden", !!loadingHidden, loadingHidden ? "" : "loading.style.display=" + (loading && loading.style && loading.style.display));
check("boot: requestAnimationFrame scheduled", rAF.pending !== null || framesRun > 0, "pending=" + !!rAF.pending);

if (errors.length) check("boot: details", false, errors[0].slice(0, 600));

if (lastError) {
  check("frames: no exception across " + framesRun + " pumped frames", false, lastError.stack ? lastError.stack.split("\n").slice(0, 3).join(" | ") : String(lastError));
} else {
  check("frames: no exception across " + framesRun + " pumped frames", true, "");
}

// the play button actually started a run
check("play: play button started a real run", playClicked,
  playClicked ? "" : "playBtn.click() failed: " + (playError ? playError.message : "no click handler"));
check("play: play-button handler did not throw", !playError, playError ? String(playError.stack || playError.message).slice(0, 400) : "");
const inMenuPersisted = body && body.classList && body.classList.contains("inmenu");
check("play: menu retired (body.inmenu cleared)", playClicked && !inMenuPersisted,
  inMenuPersisted ? "body.inmenu still set after play click — state did not advance" : "");

check("frames: loop re-scheduled (requestAnimationFrame pending)", rAF.pending !== null,
  rAF.pending ? "" : "game loop did not re-schedule");

// the run is actually running: HUD timer ticks and the wave/objective text renders
const hudTimer = sandbox.document.getElementById("timer");
const hudWave = sandbox.document.getElementById("left");
const timerText = hudTimer ? String(hudTimer.textContent) : "";
const leftText = hudWave ? String(hudWave.textContent) : "";
check("play: HUD timer ticking (run in progress)", /\d{2}:\d{2}/.test(timerText),
  "timer=" + JSON.stringify(timerText));
check("play: wave objective text rendered", /NEXT WAVE|HOSTILES|WAVE/.test(leftText),
  "left=" + JSON.stringify(leftText));

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
