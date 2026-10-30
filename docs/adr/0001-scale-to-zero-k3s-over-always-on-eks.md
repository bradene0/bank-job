# ADR-0001: Scale-to-zero k3s on one EC2 instead of always-on EKS

**Status:** Superseded by ADR-0017 (2026-10-30)

## Context
The demo gets low, bursty traffic (recruiters). An always-on EKS cluster costs about $73/month
for the control plane alone before any nodes. Managed Kafka (MSK Serverless) costs about
$0.75/hour per cluster (~$547/month). The budget target is under ~$20/month.

## Decision
Run k3s on a single `t4g.large` EC2 instance that starts on demand (wake Lambda) and stops
after 30 idle minutes. Kafka and Postgres run in-cluster. Keep an EKS Terraform module behind
a flag and use it only for short-lived load-test environments.

## Consequences
- Cost ~ $17/month instead of hundreds.
- Cold start of roughly 1–2 minutes; mitigated by replay mode and a "waking" indicator.
- Single node means no high availability for the demo; documented and accepted.
- Real multi-node orchestration is still exercised via the ephemeral EKS load-test environment.
