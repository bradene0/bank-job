output "tunnel_id" {
  value = cloudflare_zero_trust_tunnel_cloudflared.bankjob.id
}

output "live_url" {
  value = "https://${local.live_host}"
}

# Pipe it straight into the cluster secret; never print it:
#   terraform output -raw tunnel_token | kubectl ... create secret generic cloudflared --from-file=token=/dev/stdin
output "tunnel_token" {
  value     = data.cloudflare_zero_trust_tunnel_cloudflared_token.bankjob.token
  sensitive = true
}
