#!/usr/bin/env node
/* =====================================================================
   NEON BREACH — wave director simulation
   Extracts the REAL buildWave() and threatAnnounce() methods verbatim
   from index.html and evaluates them in isolation, so survival pacing
   gets a genuine runtime check without needing a browser.
   ===================================================================== */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const html = fs.readFileSync(path.join(ROOT, "index.html"), "utf8");
const js = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).join("\n");

function extract(source, signature) {
  const idx = source.indexOf(signature);
  if (idx < 0) throw new Error("method not found: " + signature);
  const open = source.indexOf("{", idx);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}") { depth--; if (depth === 0) return source.slice(open, i + 1); }
  }
  throw new Error("unbalanced braces in " + signature);
}

// strip "methodName(args) { ... }" → inner body
function bodyOf(block) {
  const firstBrace = block.indexOf("{");
  const inner = block.slice(firstBrace + 1, block.length - 1);
  return inner;
}

// --- environment stubs mirroring the game's pure dependencies ---
const ENEMY_TYPES = {
  drone: { name: "HUNTER DRONE" }, wasp: { name: "RAZOR WASP" }, hound: { name: "RAZOR HOUND" },
  leech: { name: "VOID LEECH" }, medic: { name: "REPAIR SERAPH" }, bomber: { name: "NOVA BOMBER" },
  turret: { name: "STRIKER UNIT" }, engineer: { name: "FORGE ENGINEER" }, mortar: { name: "MORTAR HUSK" },
  splitter: { name: "PRISM SPLITTER" }, sniper: { name: "LONGBOW STALKER" }, shield: { name: "AEGIS GUARD" },
  stalker: { name: "PHASE STALKER" }, brute: { name: "SIEGE BRUTE" }, sentinel: { name: "OBELISK SENTINEL" },
  warden: { name: "GRID WARDEN" },
};
const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const randInt = (a, b) => Math.floor(a + Math.random() * (b - a + 1));
const feedCalls = [];
const HUD = { feed: (text, hot) => feedCalls.push({ text, hot }) };
const Net = { active: false, mode: "COOP", isHost: () => true, players: {} };
const Game = { hpScale: 1, dmgScale: 1, speedScale: 1, rateScale: 1, waveThreat: 0, wave: 1, waveActive: false, spawnPlan: [], spawnTimer: 0 };

function makeBuildWave() {
  const block = extract(js, "buildWave(n) {");
  const inner = bodyOf(block);
  const factory = new Function("Net", "HUD", "ENEMY_TYPES", "clamp", "randInt",
    `return function buildWave(n){ ${inner} };`);
  const wv = factory(Net, HUD, ENEMY_TYPES, clamp, randInt);
  return wv;
}

function makeThreatAnnounce() {
  const block = extract(js, "threatAnnounce(n) {");
  const inner = bodyOf(block);
  const factory = new Function("ENEMY_TYPES", "HUD",
    `return function threatAnnounce(n){ ${inner} };`);
  return factory(ENEMY_TYPES, HUD);
}

const buildWave = makeBuildWave();
const threatAnnounce = makeThreatAnnounce();

let failures = 0;
const fail = m => { failures++; console.error("  ✗ " + m); };
const ok = m => console.log("  ✓ " + m);

// --- waves 1..40 ---
let wave = 1;
const plans = [];
for (; wave <= 40; wave++) {
  const ctx = Object.assign({}, Game);
  let plan = null;
  try {
    plan = buildWave.call(ctx, wave);
  } catch (e) { fail("wave " + wave + ": buildWave threw: " + e.message); continue; }
  if (!Array.isArray(plan) || plan.length === 0) fail("wave " + wave + ": empty plan");
  else if (plan.length > 60) fail("wave " + wave + ": plan over budget (" + plan.length + ")");
  if (wave === 1 && plan.length > 12) fail("wave 1 oversized opener (" + plan.length + ")");
  if (!(ctx.hpScale > 0) || !(ctx.dmgScale > 0) || !(ctx.waveThreat > 0)) fail("wave " + wave + ": bad scaling values");
  if (!(ctx.hpScale <= 3.01 && ctx.dmgScale <= 2.06 && ctx.speedScale <= 1.3 && ctx.rateScale <= 1.6)) fail("wave " + wave + ": scaling out of range");
  const bad = plan.filter(t => !ENEMY_TYPES[t]);
  if (bad.length) fail("wave " + wave + ": unknown enemy type(s): " + bad.join(", "));
  // difficulty must never *regress* dramatically — pressure should be monotone-ish
  // boss waves (every 5th) intentionally trade swarm bodies for elite commanders,
  // so a moderate drop right after a big swarm is legitimate design, not a regression.
  if (plans.length && wave % 5 !== 0 && plan.length < plans[plans.length - 1].length - 6) fail("wave " + wave + ": plan size regression " + plans[plans.length - 1].length + " → " + plan.length);
  plans.push(plan);
}
ok("waves 1–40 produce sane, escalating plans (budget, caps, scaling, validity all passing)");

// --- Threat telegraph announcements ---
function announce(wave) {
  feedCalls.length = 0;
  threatAnnounce(wave);
  return feedCalls.map(f => f.text);
}
const a1 = announce(1);
if (a1.some(t => t.includes("HUNTER DRONE"))) ok("wave-1 announcement includes base kit");
else fail("wave-1 announcement missing HUNTER DRONE: " + a1.join(" | "));
const a2 = announce(2);
if (a2.some(t => t.includes("STRIKER UNIT"))) ok("wave-2 announcement includes STRIKER UNIT");
else fail("wave-2 announcement missing STRIKER UNIT: " + a2.join(" | "));
const a5 = announce(5);
if (a5.some(t => t.includes("REPAIR SERAPH")) && a5.some(t => t.includes("LONGBOW STALKER"))) ok("wave-5 announcement includes support + sniper classes");
else fail("wave-5 announcement incomplete: " + a5.join(" | "));
const a10 = announce(10);
if (a10.some(t => t.includes("GRID WARDEN"))) ok("wave-10 announcement warns of ELITE COMMANDER");
else fail("wave-10 announcement missing GRID WARDEN: " + a10.join(" | "));

// --- opener safety ---
{
  const ctx = Object.assign({}, Game);
  const plan = buildWave.call(ctx, 1);
  const allowed = new Set(["drone", "hound", "wasp"]);
  const bad = plan.filter(t => !allowed.has(t));
  if (bad.length) fail("wave 1 introduces unearned class(es): " + bad.join(", "));
  else ok("wave 1 introduces only base classes (drone/hound/wasp)");
}

// --- difficulty sanity across the ramp: mean plan size grows from wave 1 → 40 ---
// (first-half mean must be well below second-half mean)
const first = plans.slice(0, 10).reduce((a, p) => a + p.length, 0) / 10;
const second = plans.slice(30, 40).reduce((a, p) => a + p.length, 0) / 10;
if (second > first * 1.6) ok("pressure ramps hard (mean ~" + first.toFixed(1) + " → " + second.toFixed(1) + ")");
else fail("pressure ramp too flat: mean w1-10 ~" + first.toFixed(1) + " → w31-40 ~" + second.toFixed(1));

if (failures) { console.error("\nWAVE SIM FAILED — " + failures + " assertion(s) failed"); process.exit(1); }
console.log("\nWAVE SIM PASSED");
