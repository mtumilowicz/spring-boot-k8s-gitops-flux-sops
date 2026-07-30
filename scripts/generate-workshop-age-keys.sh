#!/usr/bin/env sh
set -eu

echo
echo "DEV age key pair"
age-keygen

echo
echo "PROD age key pair"
age-keygen
