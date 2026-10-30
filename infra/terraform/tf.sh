#!/usr/bin/env bash
# Runs the pinned Terraform in Docker against this directory: infra/terraform/tf.sh plan|apply|...
# Asks for the Cloudflare API token when CLOUDFLARE_API_TOKEN is unset; it is never echoed or
# stored. State stays in this directory (local, gitignored; ADR-0017).
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]; then
  read -rsp "Cloudflare API token (hidden): " CLOUDFLARE_API_TOKEN; echo
  export CLOUDFLARE_API_TOKEN
fi
exec docker run --rm -it -v "$PWD:/w" -w /w -e CLOUDFLARE_API_TOKEN \
  hashicorp/terraform:1.16.7 "$@"
