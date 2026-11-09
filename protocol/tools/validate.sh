#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
npx --yes ajv-cli@5 validate --spec=draft2020 --strict=false \
  -s protocol/schema/messages.schema.json \
  -d 'protocol/fixtures/*.json'
bash protocol/tools/gen-identity-vectors.sh --check
