# ADR-0008: Pin every dependency and apply a 7-day release cooldown

**Status:** Accepted (2026-10-29)

## Context
Most package-registry supply-chain attacks (a hijacked maintainer account publishing a
malicious release) are detected and yanked within days. A project that resolves "latest" at
install time is exposed during that window. This repo also builds container images and runs
CI with third-party actions, which are attack surface too.

## Decision
- **Lockfiles everywhere:** `uv.lock` (Python), `pnpm-lock.yaml` (web), exact versions in
  `pom.xml` / the Spring Boot BOM (Java). CI installs with `--locked` / `--frozen-lockfile`.
- **Cooldown:** resolvers never pick a release younger than 7 days.
  - uv: `[tool.uv] exclude-newer = "7 days"` in each `pyproject.toml`.
  - pnpm: `minimumReleaseAge: 10080` (minutes) in `web/pnpm-workspace.yaml`.
  - Maven has no equivalent, so versions are bumped by hand following the same rule.
- **Integrity:** the Maven wrapper verifies the Maven distribution's SHA-256. GitHub Actions
  are pinned to full commit SHAs, with the tag in a comment.
- **Images:** base images are pinned to exact version tags.

## Consequences
- Urgent security patches take 7 days to arrive automatically; override explicitly (bump
  the pin by hand and note why in the commit) when a CVE fix cannot wait.
- Image tags are mutable; digest pinning is deferred to M6, when the release workflow and
  automated update PRs exist.
