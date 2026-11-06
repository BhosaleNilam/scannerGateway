#!/usr/bin/env bash
# Install CafeGuard Scanner Gateway to start at Mac login
# and restart itself if it crashes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/scanner-gateway-1.0.0.jar"
CONFIG="$ROOT/config/gateway.properties"
PLIST_DST="$HOME/Library/LaunchAgents/com.cafeguard.scanner.plist"

if [[ ! -f "$JAR" ]]; then
  echo "Building jar…"
  (cd "$ROOT" && mvn -q -DskipTests package)
fi

if [[ ! -f "$CONFIG" ]]; then
  echo "Missing $CONFIG"
  echo "Copy config/gateway.properties.example and set email/password."
  exit 1
fi

JAVA_BIN="$(/usr/libexec/java_home -v 21 2>/dev/null)/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then
  JAVA_BIN="$(command -v java)"
fi

mkdir -p "$ROOT/data" "$HOME/Library/LaunchAgents"

# Stop any previous agent quietly.
launchctl remove com.cafeguard.scanner 2>/dev/null || true
launchctl unload "$PLIST_DST" 2>/dev/null || true

cat > "$PLIST_DST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.cafeguard.scanner</string>
  <key>WorkingDirectory</key>
  <string>$ROOT</string>
  <key>ProgramArguments</key>
  <array>
    <string>$JAVA_BIN</string>
    <string>-jar</string>
    <string>$JAR</string>
    <string>$CONFIG</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>10</integer>
  <key>StandardOutPath</key>
  <string>$ROOT/data/gateway.out.log</string>
  <key>StandardErrorPath</key>
  <string>$ROOT/data/gateway.err.log</string>
</dict>
</plist>
EOF

launchctl load -w "$PLIST_DST"

echo
echo "Installed. Gateway starts at login and restarts if it stops."
echo "Logs: $ROOT/data/gateway.out.log / gateway.err.log"
echo
echo "REQUIRED on Mac Mini (keyboard scanner mode):"
echo "  1) System Settings → Privacy & Security → Accessibility"
echo "     → enable the Java entry (jdk / java)"
echo "  2) Then restart the agent:"
echo "       launchctl kickstart -k gui/\$(id -u)/com.cafeguard.scanner"
echo "     or simply reboot the Mac Mini"
echo
echo "Optional after power cuts:"
echo "  System Settings → Users & Groups → Automatic login → your user"
echo
echo "Stop auto-start later:"
echo "  launchctl unload ~/Library/LaunchAgents/com.cafeguard.scanner.plist"
