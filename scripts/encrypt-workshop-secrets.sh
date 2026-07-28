#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/../gitops/overlays/dev"
sops --encrypt --in-place secret.enc.yaml

cd ../prod
sops --encrypt --in-place secret.enc.yaml
