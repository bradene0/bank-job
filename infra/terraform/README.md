# infra/terraform

Modules: network (public subnets, no NAT), compute (t4g.large, EBS, instance profile, security group allowing 443 only from Cloudflare IP ranges), dns (Cloudflare provider: records in the existing bradenevans.dev zone; never creates or deletes the zone), edge (S3 + CloudFront + ACM in us-east-1, validated via Cloudflare CNAMEs), wake (Lambda), ssm, budgets, eks (behind a flag). Envs: prod, loadtest. S3 backend with use_lockfile. Built in M6. Applied manually, never from automation.
