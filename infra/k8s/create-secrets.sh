#!/usr/bin/env bash
# Creates the production secrets in the cluster, from this machine only. Never overwrites a
# secret that exists (a new Postgres password would lock game-api out of its data), so it is
# safe to run again. Values are generated or prompted for and never printed.
set -euo pipefail
cd "$(dirname "$0")"
. ./env.sh

kc apply -f namespace.yaml >/dev/null
exists() { kc -n bankjob get secret "$1" >/dev/null 2>&1; }

if exists postgres; then echo "postgres: exists, kept"; else
  kc -n bankjob create secret generic postgres \
    --from-literal=password="$(openssl rand -hex 24)" >/dev/null
  echo "postgres: created"
fi

if exists game-api; then echo "game-api: exists, kept"; else
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  (
    umask 077
    # ES256 key bundle in the format game-api reads (private key, then public key).
    openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out "$tmp/priv.pem"
    openssl ec -in "$tmp/priv.pem" -pubout -out "$tmp/pub.pem" 2>/dev/null
    cat "$tmp/priv.pem" "$tmp/pub.pem" > "$tmp/k.pem"
  )
  kc -n bankjob create secret generic game-api \
    --from-file=jwt-es256.pem="$tmp/k.pem" \
    --from-literal=ip-hash-salt="$(openssl rand -hex 32)" >/dev/null
  echo "game-api: created"
fi

if exists agent; then echo "agent: exists, kept"; else
  read -rsp "Paste the bankjob-game Anthropic API key (input hidden): " key; echo
  [[ "$key" == sk-ant-* ]] || { echo "that does not look like an Anthropic key" >&2; exit 1; }
  printf '%s' "$key" | kc -n bankjob create secret generic agent \
    --from-file=anthropic-api-key=/dev/stdin >/dev/null
  unset key
  echo "agent: created"
fi

if exists cloudflared; then echo "cloudflared: exists, kept"; else
  # Read from the local Terraform state; piped straight into the cluster.
  docker run --rm -v "$PWD/../terraform:/w" -w /w hashicorp/terraform:1.16.7 \
    output -raw tunnel_token | kc -n bankjob create secret generic cloudflared \
    --from-file=token=/dev/stdin >/dev/null
  echo "cloudflared: created"
fi
