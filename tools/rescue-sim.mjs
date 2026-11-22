#!/usr/bin/env node
/* =====================================================================
   NEON BREACH — co-op rescue simulation
   Extracts the REAL updateRescue() from index.html and checks the
   rescue channel math: progress advances at fixed rate, completes at 100%,
   cancels when the rescuer is missing / dead / out of range, and bails
   safely in PVP mode.
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
function bodyOf(block) { return block.slice(block.indexOf("{") + 1, block.length - 1); }

const block = extract(js, "updateRescue(p,dt,now){");
const factory = new Function("HUD", `return function updateRescue(p,dt,now){ ${bodyOf(block)} };`);
const HUD = { feed: () => {} };
const updateRescue = factory(HUD);

let failures = 0;
const fail = m => { failures++; console.error("  ✗ " + m); };
const ok = m => console.log("  ✓ " + m);

// stub Net: a host-like authority
function makeNet(mode) {
  const calls = { revives: 0 };
  return {
    mode,
    players: {},
    revivePlayer(p) { calls.revives++; /* marker: completion advanced past 100% */ p.alive = true; },
    calls,
  };
}

// --- baseline: progress advances, completion triggers revive ---
{
  const Net = makeNet("COOP");
  const p = { id: "P-TARGET", alive: false, entered: true, x: 0, z: 0, rescueById: "P-RESCUER", rescueActive: true, rescueProgress: 0, invT: 0 };
  Net.players["P-RESCUER"] = { id: "P-RESCUER", alive: true, entered: true, x: 2, z: 1 };
  const dt = 0.05;
  for (let i = 0; i < 60; i++) updateRescue.call(Net, p, dt, performance.now());
  if (Net.calls.revives === 0) fail("rescue never completed after 3s of channeling (dt-based)");
  else ok("rescue completes after ~2.2s of channeling");

  // --- progress accumulates per frame (not quadratically) ---
  const p2 = { id: "P-T", alive: false, entered: true, x: 0, z: 0, rescueById: "P-R", rescueActive: true, rescueProgress: 0 };
  Net.players["P-R"] = { id: "P-R", alive: true, entered: true, x: 1, z: 0 };
  updateRescue.call(Net, p2, 0.05, performance.now());
  if (Math.abs(p2.rescueProgress - 0.05 / 2.2) < 1e-4) ok("rescue progress advances exactly dt/2.2 per frame");
  else fail("rescue progress advances wrong rate: got " + p2.rescueProgress);
}

// --- cancels when the rescuer dies ---
{
  const Net = makeNet("COOP");
  const p = { id: "P-T", alive: false, entered: true, x: 0, z: 0, rescueById: "P-R", rescueActive: true, rescueProgress: 0.9 };
  Net.players["P-R"] = { id: "P-R", alive: false, entered: true, x: 1, z: 0 };
  updateRescue.call(Net, p, 0.05, performance.now());
  if (p.rescueActive || p.rescueProgress !== 0) fail("rescue did not cancel on rescuer death");
  else ok("rescue cancels when rescuer goes down");
}

// --- cancels when rescuer leaves range ---
{
  const Net = makeNet("COOP");
  const p = { id: "P-T", alive: false, entered: true, x: 0, z: 0, rescueById: "P-R", rescueActive: true, rescueProgress: 0.4 };
  Net.players["P-R"] = { id: "P-R", alive: true, entered: true, x: 30, z: 30 };
  updateRescue.call(Net, p, 0.05, performance.now());
  if (p.rescueActive || p.rescueProgress !== 0) fail("rescue did not cancel on out-of-range rescuer");
  else ok("rescue cancels when rescuer leaves 4.6m range");
}

// --- refuses in PVP mode ---
{
  const Net = makeNet("PVP");
  const p = { id: "P-T", alive: false, entered: true, x: 0, z: 0, rescueById: "P-R", rescueActive: true, rescueProgress: 0.2 };
  Net.players["P-R"] = { id: "P-R", alive: true, entered: true, x: 1, z: 0 };
  updateRescue.call(Net, p, 0.05, performance.now());
  if (p.rescueProgress !== 0 || p.rescueActive) fail("rescue progressed in PVP mode (must stay COOP-only)");
  else ok("rescue is COOP-only (refused in PVP)");
}

// --- survives a missing rescuer record entirely ---
{
  const Net = makeNet("COOP");
  const p = { id: "P-T", alive: false, entered: true, x: 0, z: 0, rescueById: "P-GHOST", rescueActive: true, rescueProgress: 0.6 };
  updateRescue.call(Net, p, 0.05, performance.now());
  if (p.rescueProgress !== 0 || p.rescueActive) fail("rescue survived a missing rescuer record");
  else ok("rescue cancels when rescuer record is missing");
}

if (failures) { console.error("\nRESCUE SIM FAILED — " + failures + " assertion(s) failed"); process.exit(1); }
console.log("\nRESCUE SIM PASSED");
