#!/usr/bin/env bash
# Builds the game for production and deploys it with the replay files as Workers static assets.
# Usage: web/scripts/deploy-worker.sh <replay dir> [summary.json]
# CI runs this with the eval matrix's replay files. It uploads and deploys a new version only, so
# the Worker's custom domain is never touched and a token with Editor on this one Worker is
# enough. (The Worker and its domain were created once by hand with `wrangler deploy`.)
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
tag="${RELEASE_TAG:-manual-$(date -u +%Y%m%d%H%M%S)}"
pnpm exec wrangler versions upload --tag "$tag" --message "Bank Job ${tag}"
pnpm exec wrangler versions deploy --version-tag "${tag}@100" --yes
