terraform {
  required_version = "~> 1.16.7"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "5.28.1"
    }
  }

  # No backend block: state stays local on the owner's machine and is never committed or
  # read by CI. A remote backend can be added later with `terraform init -migrate-state`.
}

# Reads CLOUDFLARE_API_TOKEN from the environment.
provider "cloudflare" {}
