#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

mkdir -p build/tmp

DEV_KEY_FILE="build/tmp/dev-age-key.txt"
PROD_KEY_FILE="build/tmp/prod-age-key.txt"

rm -f "$DEV_KEY_FILE" "$PROD_KEY_FILE"

age-keygen -o "$DEV_KEY_FILE"
age-keygen -o "$PROD_KEY_FILE"

echo
echo "DEV public key -> gitops/overlays/dev/.sops.yaml"
sed -n 's/^# public key: //p' "$DEV_KEY_FILE"

echo
echo "DEV private key -> gitops/clusters/dev/flux-sops-age-key-bootstrap.yaml"
cat "$DEV_KEY_FILE"

echo
echo "PROD public key -> gitops/overlays/prod/.sops.yaml"
sed -n 's/^# public key: //p' "$PROD_KEY_FILE"

echo
echo "PROD private key -> gitops/clusters/prod/flux-sops-age-key-bootstrap.yaml"
cat "$PROD_KEY_FILE"

rm -f "$DEV_KEY_FILE" "$PROD_KEY_FILE"
