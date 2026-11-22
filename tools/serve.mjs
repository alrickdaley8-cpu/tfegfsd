#!/usr/bin/env node
/* =====================================================================
   NEON BREACH — zero-dependency static server
   `npm run serve`  →  http://127.0.0.1:8080 (or PORT=xxxx)
   Serves the repo root so index.html, manifest, icons all resolve
   exactly as they do on GitHub Pages.
   ===================================================================== */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || "0.0.0.0";

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".txt": "text/plain; charset=utf-8",
  ".md": "text/markdown; charset=utf-8",
  ".woff2": "font/woff2",
};

const server = http.createServer((req, res) => {
  try {
    const urlPath = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const rel = urlPath === "/" ? "/index.html" : urlPath;
    const file = path.normalize(path.join(ROOT, rel));
    if (!file.startsWith(ROOT)) {
      res.writeHead(403, { "Content-Type": "text/plain" });
      res.end("Forbidden");
      return;
    }
    fs.stat(file, (err, st) => {
      if (err || !st.isFile()) {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("Not found: " + rel);
        return;
      }
      const type = MIME[path.extname(file).toLowerCase()] || "application/octet-stream";
      res.writeHead(200, {
        "Content-Type": type,
        "Content-Length": st.size,
        "Cache-Control": "no-cache",
        "X-Content-Type-Options": "nosniff",
      });
      fs.createReadStream(file).pipe(res);
    });
  } catch (e) {
    res.writeHead(500, { "Content-Type": "text/plain" });
    res.end("Server error: " + e.message);
  }
});

server.listen(PORT, HOST, () => {
  console.log("NEON BREACH dev server → http://" + HOST + ":" + PORT);
  console.log("  Ctrl+C to stop. Bound to " + HOST + " (0.0.0.0 for preview access).");
});

process.on("SIGTERM", () => process.exit(0));
process.on("SIGINT", () => process.exit(0));
