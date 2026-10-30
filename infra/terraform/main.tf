locals {
  account_id = var.account_id
  live_host  = "${var.live_subdomain}.${var.zone_name}"
}

# Remotely managed tunnel: its routes live in Cloudflare (below), and cloudflared in the
# cluster only needs the tunnel token. It connects outbound, so the host opens no ports.
resource "cloudflare_zero_trust_tunnel_cloudflared" "bankjob" {
  account_id = local.account_id
  name       = "bankjob"
  config_src = "cloudflare"
}

resource "cloudflare_zero_trust_tunnel_cloudflared_config" "bankjob" {
  account_id = local.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.bankjob.id

  config = {
    ingress = [
      {
        # Only the public API reaches the cluster. /mcp, actuator and anything else stop here,
        # and the Traefik ingress allowlists the same paths again.
        hostname = local.live_host
        path     = "^/(v1/.*|healthz)$"
        service  = var.ingress_service
      },
      {
        service = "http_status:404"
      },
    ]
  }
}

resource "cloudflare_dns_record" "live" {
  zone_id = var.zone_id
  name    = local.live_host
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.bankjob.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1 # automatic; required for proxied records
  comment = "Bank Job live API via Cloudflare Tunnel (managed by Terraform in bank-job)"
}

data "cloudflare_zero_trust_tunnel_cloudflared_token" "bankjob" {
  account_id = local.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.bankjob.id
}
