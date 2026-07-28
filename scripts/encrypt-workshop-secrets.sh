#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/../gitops"

sops --encrypt --in-place overlays/dev/secret.enc.yaml
sops --encrypt --in-place overlays/prod/secret.enc.yaml
