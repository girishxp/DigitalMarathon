#!/bin/bash
set -euo pipefail
BASE="$(cd "$(dirname "$0")" && pwd)"
exec /bin/bash "$BASE/app-files/scripts/publish-github.sh" "$@"
