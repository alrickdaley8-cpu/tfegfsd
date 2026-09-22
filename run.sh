#!/usr/bin/env bash
# T2V·LAB — local text-to-video studio
set -e
cd "$(dirname "$0")"
PORT="${PORT:-8000}"
echo "▶ T2V·LAB on http://0.0.0.0:${PORT}"
exec python3 -m uvicorn server.app:app --host 0.0.0.0 --port "${PORT}"
