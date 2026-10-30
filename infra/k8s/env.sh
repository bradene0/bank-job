# shellcheck shell=bash
# Sourced by the scripts here: kubectl for the homelab cluster through an SSH port-forward
# (the VM only accepts SSH from the admin machine; ADR-0017). Expects the `bankjob` SSH host
# alias and ~/.kube/bankjob.yaml (see README.md).
export KUBECONFIG="${KUBECONFIG_BANKJOB:-$HOME/.kube/bankjob.yaml}"
kc() { kubectl --context bankjob "$@"; }
if ! pgrep -f '6443:127.0.0.1:6443' >/dev/null; then
  ssh -fN -L 6443:127.0.0.1:6443 bankjob
fi
kc get --raw /readyz >/dev/null
