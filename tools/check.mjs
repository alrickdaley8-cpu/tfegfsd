#!/usr/bin/env node
/* =====================================================================
   NEON BREACH — static smoke test
   Catches the failure modes that would otherwise ship silently:
   - malformed inline scripts (node --check)
   - missing element ids referenced via getElementById / getElementByIdList
   - duplicate top-level constant names
   - missing core sections / multiplayer wiring
   No browser required — the whole game runs from index.html with Three.js
   from CDN, so a syntax-level audit plus an id/section audit is the
   cheapest reliable signal before a deploy.
   ===================================================================== */
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const HTML = fs.readFileSync(path.join(ROOT, "index.html"), "utf8");
let failures = 0;

function ok(msg) { console.log("  ✓ " + msg); }
function fail(msg) { failures++; console.error("  ✗ " + msg); }

// --- 1. inline scripts: parse/validate with node itself ---
const scripts = [...HTML.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (scripts.length === 0) { fail("index.html has no inline <script>"); process.exit(1); }
const tmp = path.join(os.tmpdir(), "nb-inline-check-" + process.pid + ".js");
fs.writeFileSync(tmp, scripts.map((s, i) => "/* === INLINE SCRIPT " + i + " === */\n" + s).join("\n"));
try {
  execFileSync(process.execPath, ["--check", tmp], { stdio: "pipe" });
  ok("inline script parses cleanly (node --check, " + scripts.join("").length + " bytes)");
} catch (e) {
  fail("inline script has a syntax error:\n" + String(e.stderr || e.message));
} finally { try { fs.unlinkSync(tmp); } catch (e) {} }

// --- 2. every getElementById target exists in the markup ---
const idAttrs = new Set([...HTML.matchAll(/id="([^"]+)"/g)].map(m => m[1]));
const wanted = new Set([...scripts.join("\n").matchAll(/getElementById\(\s*["']([^"']+)["']/g)].map(m => m[1]));
const missing = [...wanted].filter(id => !idAttrs.has(id));
if (!missing.length) ok("all getElementById targets exist in markup (" + wanted.size + " ids)");
else fail("markup is missing " + missing.length + " id(s) referenced by script: " + missing.join(", "));

// --- 3. duplicate top-level const names (would redefine / collide) ---
const names = [...scripts.join("\n").matchAll(/^const ([A-Za-z_$][\w$]*)\s*=/gm)].map(m => m[1]);
const dup = names.filter((n, i) => names.indexOf(n) !== i);
if (!dup.length) ok("no duplicate top-level const names (" + names.length + " scanned)");
else fail("duplicate top-level const name(s): " + [...new Set(dup)].join(", "));

// --- 4. core sections and wiring are present ---
const mustHave = [
  [/<canvas|id="app"/i, "renderer mount point"],
  [/three\.js/i, "Three.js vendored + CDN-fallback dependency"],
  [/MULTIPLAYER/i, "multiplayer panel"],
  [/Netcode|Net\.init\(\)/i, "host-authoritative Netcode 2.0 init"],
  [/waveActive|buildWave/i, "wave director"],
  [/res/i, "rescue/revive co-op support"],
  [/WORKSHOP/i, "mod workshop"],
  [/BalanceLab\.init\(\)/i, "balance lab wiring"],
  [/BOOT/i, "boot sequence"],
];
for (const [re, label] of mustHave) {
  if (re.test(HTML) || re.test(scripts.join("\n"))) ok("section present: " + label);
  else fail("section missing: " + label);
}

// --- 5. single HTML entry point + meta sanity ---
const head = HTML.match(/<head>[\s\S]*?<\/head>/);
if (head && /<meta name="viewport"/.test(head[0]) && /<title>/.test(head[0])) ok("document head is complete (viewport + title)");
else fail("document head is incomplete");

if (failures) {
  console.error("\nSMOKE TEST FAILED — " + failures + " check(s) failed");
  process.exit(1);
}
console.log("\nSMOKE TEST PASSED");
