#!/usr/bin/env bash
# Builds the game for production and deploys it with the replay files as Workers static assets.
# Usage: web/scripts/deploy-worker.sh <replay dir> [summary.json]
# CI runs this with the eval matrix's replay files; the owner runs it once by hand to create the
# Worker and its custom domain (wrangler asks to log in when CLOUDFLARE_API_TOKEN is unset).
set -euo pipefail
replays="$(cd "${1:?replay directory (services/evals/out/replay)}" && pwd)"
summary="${2:-}"
[[ -z "$summary" ]] || summary="$(cd "$(dirname "$summary")" && pwd)/$(basename "$summary")"
cd "$(dirname "$0")/.."

count="$(find "$replays" -name '*.json' | wc -l | tr -d ' ')"
[[ "$count" -gt 0 ]] || { echo "no replay files in $replays" >&2; exit 1; }

VITE_API_URL=https://bankjob-live.bradenevans.dev pnpm build
pnpm check:bundle
rm -rf dist/replay
cp -R "$replays" dist/replay
[[ -z "$summary" ]] || cp "$summary" dist/replay/summary.json
echo "dist: $(find dist -type f | wc -l | tr -d ' ') files, $count replay files"
pnpm exec wrangler deploy
