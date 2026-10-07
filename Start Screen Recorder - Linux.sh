#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
INTERNAL="$ROOT/app-files"
chmod +x "$INTERNAL"/*.sh "$INTERNAL"/scripts/*.sh 2>/dev/null || true
exec /bin/bash "$INTERNAL/run_linux.sh"
