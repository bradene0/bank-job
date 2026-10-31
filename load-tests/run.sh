#!/usr/bin/env bash
# Runs a k6 profile against the local compose stack (stub mode), from the pinned k6 image on the
# compose network, and writes the summary to load-tests/results/.
#   load-tests/run.sh steady     # the SLO check: 20 players, 3 minutes
#   load-tests/run.sh stress     # ramp to 120 players, no think time
# The stack must run with LLM_MODE=stub and a session limit above the run's sessions:
#   SESSIONS_MAX_PER_IP_PER_HOUR=20000 docker compose up -d --wait
set -euo pipefail

profile="${1:-steady}"
here="$(cd "$(dirname "$0")" && pwd)"
k6_image="grafana/k6:1.0.0"

mode="$(docker compose -f "$here/../docker-compose.yml" exec -T game-api printenv LLM_MODE)"
if [ "$mode" != "stub" ]; then
  echo "game-api runs LLM_MODE=$mode; load tests run in stub mode only (no model spend)" >&2
  exit 1
fi

mkdir -p "$here/results"
docker run --rm --network bankjob_default \
  -e PROFILE="$profile" \
  -e OUT="/results/$profile" \
  -v "$here/scenarios:/scenarios:ro" \
  -v "$here/results:/results" \
  "$k6_image" run --quiet /scenarios/players.js
