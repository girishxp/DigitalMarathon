#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/../Digital Marathon.app"
BUNDLE_ID="com.girishgupta.inputactivitytracker"

osascript -e 'tell application "Digital Marathon" to quit' >/dev/null 2>&1 || true
sleep 1

# Reset only this application's input-related TCC records. macOS will ask again.
/usr/bin/tccutil reset ListenEvent "$BUNDLE_ID" >/dev/null 2>&1 || true
/usr/bin/tccutil reset Accessibility "$BUNDLE_ID" >/dev/null 2>&1 || true

open "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent" || true

cat <<'MSG'
Digital Marathon permissions were reset.

1. In System Settings > Privacy & Security > Input Monitoring, enable the Digital Marathon application.
2. Accessibility can also be enabled as a fallback.
3. The app retries automatically, so the Keys total should begin changing after
   permission is enabled. If macOS asks, quit and reopen the app once.
MSG

if [[ -d "$APP" ]]; then
  open "$APP"
else
  echo
  echo "The app is missing. Extract the complete ZIP again, then open Digital Marathon.app."
fi

echo
read -r -p "Press Return to close this window..." _
