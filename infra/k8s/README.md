# infra/k8s

Production workloads for the homelab k3s node (ADR-0017): Postgres and Kafka (StatefulSets on
local-path volumes), Jaeger, game-api (native image), the agent, cloudflared, the Traefik
ingress for `/v1/*` and `/healthz`, and NetworkPolicies that deny all ingress except the paths
each workload needs. `/mcp` is reachable only from the agent.

Run from the admin machine (CI never reaches the cluster):

| Script | What it does |
|---|---|
| `create-secrets.sh` | Creates the Postgres password, JWT key, IP salt, Anthropic key (prompted) and tunnel token (from Terraform state). Never overwrites, never prints |
| `deploy.sh [commit]` | Applies the manifests with the commit's images pinned by digest and waits for rollouts |

Both expect an SSH host alias `bankjob` and the cluster's kubeconfig at `~/.kube/bankjob.yaml`
(context `bankjob`); `env.sh` opens the SSH port-forward to the API server when needed.
