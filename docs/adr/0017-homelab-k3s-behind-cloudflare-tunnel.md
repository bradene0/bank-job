# ADR-0017: Production runs on a homelab VM behind a Cloudflare Tunnel

**Status:** Accepted (2026-10-30). Supersedes ADR-0001.

## Context
ADR-0001 put the backend on one AWS EC2 instance that starts on demand and stops when idle.
Most of its moving parts existed only to make an always-on VM cheap: the wake Lambda, the
idle-reaper, the boot-time DNS update, the Origin CA certificate and the security group that
admits only Cloudflare. AWS is no longer an option for this project, and the stack is small: the
compose limits add up to about 2.5 GiB (Postgres, Kafka, Jaeger, game-api, agent).

Options considered:
1. Another cloud VM (GCP, Azure, Hetzner): same design, $8-60 a month depending on hours awake.
2. Cloudflare Containers: scale-to-zero is built in, but container disk is wiped on sleep, so
   Postgres and Kafka would need snapshots, and the whole stack would share one container.
3. An always-on homelab VM, published through a Cloudflare Tunnel.

## Decision
Option 3.

- One Ubuntu 24.04 VM on the owner's Proxmox host (x86_64, 4 vCPU with the host CPU type for
  AVX2, 8 GiB, 60 GiB) runs k3s and every in-cluster workload.
- `cloudflared` runs in the cluster and connects outbound to Cloudflare. `live.bankjob` is a
  proxied CNAME to the tunnel. The tunnel forwards only `/v1/*` and `/healthz` to Traefik, and
  the Traefik ingress allowlists the same paths. `/mcp` is never public.
- The VM has its own firewall: inbound SSH from the owner's admin machine only; outbound DNS to
  the router, no private or tailnet ranges, the internet otherwise.
- The game UI and the replay files are Workers static assets on `bankjob`, deployed by wrangler.
- Terraform manages the Cloudflare side (tunnel, routes, DNS). Its state is local to the owner's
  machine and never committed; applies are manual, never from CI.
- New runtime service: `cloudflared`. No new paid service.

## Consequences
- Hosting costs $0 beyond electricity; the Anthropic workspace limit is the only spend.
- No cold start: the API is always on, so the wake Lambda, idle-reaper and EKS load-test module
  are dropped. Replay mode still covers any outage (the host, the tunnel, the model budget).
- No public IP, open port or origin certificate to manage. Cloudflare terminates TLS.
- Availability is the homelab's, with no redundancy; accepted for a demo.
- CI cannot reach the cluster or the state. It builds and pushes images and runs Terraform
  `fmt`, `validate` and tflint; plans, applies and cluster deploys run from the owner's machine.
- Load tests run against the local compose stack (stub mode), not production.
