variable "zone_name" {
  description = "The existing Cloudflare zone. Terraform reads it and never creates or deletes it."
  type        = string
  default     = "bradenevans.dev"
}

variable "live_subdomain" {
  description = "Subdomain of zone_name that serves the live API through the tunnel."
  type        = string
  default     = "live.bankjob"
}

variable "ingress_service" {
  description = "In-cluster origin the tunnel forwards to (k3s's bundled Traefik)."
  type        = string
  default     = "http://traefik.kube-system.svc.cluster.local:80"
}
