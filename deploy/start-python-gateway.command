#!/bin/bash
# Double-click on macOS (or run from Terminal).
cd "$(dirname "$0")/.." || exit 1
CONFIG="config/gateway.properties"
if [[ ! -f "$CONFIG" ]]; then
  echo "Missing $CONFIG"
  echo "Copy config/gateway.properties.example and set cafe email/password."
  read -r _
  exit 1
fi
exec python3 scripts/fiscal_scan_gateway.py "$CONFIG"
