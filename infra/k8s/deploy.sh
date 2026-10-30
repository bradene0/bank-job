#!/usr/bin/env bash
# Deploys a release to the homelab cluster: the images CI pushed for a commit, pinned by
# digest. Usage: infra/k8s/deploy.sh [commit]   (default: origin/main)
set -euo pipefail
cd "$(dirname "$0")"
. ./env.sh

git fetch -q origin main
sha="$(git rev-parse "${1:-origin/main}")"
registry=ghcr.io/bradene0/bank-job

render="$(kubectl kustomize --load-restrictor LoadRestrictionsNone .)"
for image in game-api agent; do
  digest="$(docker buildx imagetools inspect "$registry/$image:sha-$sha" --format '{{.Manifest.Digest}}')"
  echo "$image: sha-${sha:0:12} -> $digest"
  render="${render//"$registry/$image:main"/"$registry/$image@$digest"}"
done

printf '%s\n' "$render" | kc apply -f -
for workload in statefulset/postgres statefulset/kafka deployment/jaeger \
  deployment/agent deployment/game-api deployment/cloudflared; do
  kc -n bankjob rollout status "$workload" --timeout=5m
done
echo "Deployed ${sha:0:12}. Check: curl -fsS https://live.bankjob.bradenevans.dev/healthz"
