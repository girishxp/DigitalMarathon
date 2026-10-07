#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUNDLE_ID="com.girishgupta.inputactivitytracker"

osascript -e 'tell application "Digital Marathon" to quit' >/dev/null 2>&1 || true
sleep 1

# Every local rebuild changes an ad-hoc code signature. Remove stale TCC records
# before creating the new bundle so the visible switch and the executable being
# monitored cannot refer to different builds.
/usr/bin/tccutil reset ListenEvent "$BUNDLE_ID" >/dev/null 2>&1 || true
/usr/bin/tccutil reset Accessibility "$BUNDLE_ID" >/dev/null 2>&1 || true

/bin/bash "$ROOT/scripts/build_unix.sh" mac clean

# The newly opened app requests access. Open the exact settings page as well so
# the user can enable the new bundle immediately.
open "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent" >/dev/null 2>&1 || true

cat <<'MSG'

A fresh Digital Marathon bundle was built and its stale permission records
were cleared. In System Settings > Privacy & Security > Input Monitoring, enable
the newly built Digital Marathon. Enable Accessibility too if macOS lists
it there. The running app retries automatically while permissions are being enabled.
MSG
