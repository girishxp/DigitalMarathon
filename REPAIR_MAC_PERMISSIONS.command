#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
INTERNAL="$ROOT/app-files"
xattr -dr com.apple.quarantine "$ROOT" 2>/dev/null || true
chmod +x "$INTERNAL/repair_permissions_mac.command" 2>/dev/null || true
exec /bin/bash "$INTERNAL/repair_permissions_mac.command"
