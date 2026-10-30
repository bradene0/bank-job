# IDs, not secrets. Passing them avoids a zone lookup, so the API token needs no Zone Read.
variable "account_id" {
  description = "Cloudflare account that owns the zone."
  type        = string
  default     = "8d38c5eb5e8e2e751921876904264f9f"
}

variable "zone_id" {
  description = "The existing bradenevans.dev zone. Terraform never creates or deletes it."
  type        = string
  default     = "a84f13980b82b8f393c3cc34947676b0"
}

variable "zone_name" {
  description = "Name of the zone with zone_id."
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
