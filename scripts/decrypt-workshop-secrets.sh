#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/../gitops"

if grep -q '^sops:' overlays/dev/secret.enc.yaml; then
  SOPS_AGE_KEY="$(grep 'AGE-SECRET-KEY-' clusters/dev/flux-sops-age-key-bootstrap.yaml | sed 's/^[[:space:]]*//')"
  export SOPS_AGE_KEY
  echo
  echo "== overlays/dev/secret.enc.yaml"
  sops --decrypt overlays/dev/secret.enc.yaml
else
  echo "overlays/dev/secret.enc.yaml is already plaintext"
fi

if grep -q '^sops:' overlays/prod/secret.enc.yaml; then
  SOPS_AGE_KEY="$(grep 'AGE-SECRET-KEY-' clusters/prod/flux-sops-age-key-bootstrap.yaml | sed 's/^[[:space:]]*//')"
  export SOPS_AGE_KEY
  echo
  echo "== overlays/prod/secret.enc.yaml"
  sops --decrypt overlays/prod/secret.enc.yaml
else
  echo "overlays/prod/secret.enc.yaml is already plaintext"
fi
