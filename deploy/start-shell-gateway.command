#!/bin/bash
cd "$(dirname "$0")/.." || exit 1
CONFIG="config/gateway.properties"
if [[ ! -f "$CONFIG" ]]; then
  echo "Missing $CONFIG — copy gateway.properties.example and set cafe login."
  read -r _
  exit 1
fi
chmod +x scripts/fiscal_scan_gateway.sh
exec ./scripts/fiscal_scan_gateway.sh "$CONFIG"
