# infra/terraform

The Cloudflare side of production (ADR-0017): the `bankjob` tunnel, its routes, and the proxied
`bankjob-live` record in the existing `bradenevans.dev` zone. Terraform never creates or deletes
the zone. The game UI Worker is deployed by wrangler, not here.

- State is local to the owner's machine. It is never committed, never read by CI, and may hold
  secrets (the tunnel token), so never print it. Back it up by hand after each apply.
- Applied by hand, never from automation. CI runs `fmt`, `validate` and tflint only.
- Needs `CLOUDFLARE_API_TOKEN` in the environment.

```sh
TF="docker run --rm -it -v $PWD:/w -w /w -e CLOUDFLARE_API_TOKEN hashicorp/terraform:1.16.7"
$TF init
$TF plan
```
