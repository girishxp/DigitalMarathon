#!/bin/bash
# Owner-operated publisher. No credentials are embedded and dry-run never mutates GitHub.
set -euo pipefail
VERSION=2.1.31
GITHUB_REPO=girishxp/DigitalMarathon
# Explicit host keeps an unrelated enterprise login or GH_HOST out of this publisher.
GITHUB_TARGET="github.com/$GITHUB_REPO"
BRANCH=main
PACKAGE="$(cd "$(dirname "$0")/../.." && pwd)"
PARENT="$(dirname "$PACKAGE")"
REPO_DIR="${DIGITAL_MARATHON_REPO:-$HOME/Developer/DigitalMarathon}"
SOURCE="${DIGITAL_MARATHON_SOURCE:-}"
# The release ZIP already contains the source. A separate snapshot is optional.
ZIP_NAME="digital-marathon-cross-platform-v$VERSION-click-to-launch.zip"
CHECKSUM_NAME="SHA256SUMS-v$VERSION.txt"
FEED_NAME="digital-marathon-update.json"
ZIP_PATH="${DIGITAL_MARATHON_ZIP:-$PARENT/$ZIP_NAME}"
CHECKSUM="${DIGITAL_MARATHON_CHECKSUM:-$PARENT/$CHECKSUM_NAME}"
CHECKSUM_EXPLICIT=0
[ -z "${DIGITAL_MARATHON_CHECKSUM:-}" ] || CHECKSUM_EXPLICIT=1
DRY_RUN=0
TMP=""
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { if [ -n "$TMP" ] && [ -d "$TMP" ]; then rm -rf "$TMP"; fi; }
trap cleanup EXIT
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --source|--zip|--checksum|--repo-dir)
      [ "$#" -ge 2 ] || fail "Missing value for $1"
      [ -n "$2" ] || fail "Empty value for $1"
      case "$1" in --source) SOURCE="$2" ;; --zip) ZIP_PATH="$2" ;; --checksum) CHECKSUM="$2"; CHECKSUM_EXPLICIT=1 ;; --repo-dir) REPO_DIR="$2" ;; esac
      shift 2 ;;
    --help) printf '%s\n' 'Usage: Publish Digital Marathon.command [--dry-run] [--source PATH] [--zip PATH] [--checksum PATH] [--repo-dir PATH]'; exit 0 ;;
    *) fail "Unknown argument: $1" ;;
  esac
done
for command in git gh unzip shasum cmp find awk python3; do command -v "$command" >/dev/null || fail "Install $command before publishing. See PUBLISHING.md."; done
if [ ! -f "$ZIP_PATH" ] && [ "$ZIP_PATH" = "$PARENT/$ZIP_NAME" ]; then ZIP_PATH="$HOME/Downloads/$ZIP_NAME"; fi
if [ "$CHECKSUM_EXPLICIT" -eq 0 ] && [ ! -f "$CHECKSUM" ]; then CHECKSUM="$(dirname "$ZIP_PATH")/$CHECKSUM_NAME"; fi
[ -f "$ZIP_PATH" ] && [ "$(basename "$ZIP_PATH")" = "$ZIP_NAME" ] || fail "Expected the exact $ZIP_NAME build. Keep the original ZIP beside digital-marathon, or use --zip PATH."
if [ "$CHECKSUM_EXPLICIT" -eq 1 ] || [ -e "$CHECKSUM" ]; then
  [ -f "$CHECKSUM" ] && [ "$(basename "$CHECKSUM")" = "$CHECKSUM_NAME" ] || fail "Expected the supplied $CHECKSUM_NAME checksum file."
else
  CHECKSUM=""
fi
if [ -n "$SOURCE" ]; then [ -d "$SOURCE" ] || fail "Supplied source snapshot missing: $SOURCE."; fi
[ -d "$REPO_DIR" ] || fail "Source checkout missing: $REPO_DIR. See first-time setup in PUBLISHING.md."
REPO_DIR="$(cd "$REPO_DIR" && pwd -P)"
[ "$(git -C "$REPO_DIR" rev-parse --show-toplevel)" = "$REPO_DIR" ] || fail "Use the repository root as --repo-dir."
[ "$(git -C "$REPO_DIR" symbolic-ref --short HEAD)" = "$BRANCH" ] || fail "Checkout must be on main."
[ -z "$(git -C "$REPO_DIR" status --porcelain --untracked-files=all)" ] || fail "Checkout has uncommitted files; commit them before publishing."
git -C "$REPO_DIR" var GIT_AUTHOR_IDENT >/dev/null || fail "Configure Git author name/email before publishing."
git -C "$REPO_DIR" var GIT_COMMITTER_IDENT >/dev/null || fail "Configure Git committer name/email before publishing."
ORIGIN="$(git -C "$REPO_DIR" remote get-url origin)"
case "$ORIGIN" in
  https://github.com/girishxp/DigitalMarathon|https://github.com/girishxp/DigitalMarathon.git|git@github.com:girishxp/DigitalMarathon|git@github.com:girishxp/DigitalMarathon.git|ssh://git@github.com/girishxp/DigitalMarathon.git) ;;
  *) fail "Origin must be exactly girishxp/DigitalMarathon on github.com, with no embedded credentials." ;;
esac
[ -z "$(find "$REPO_DIR" -path "$REPO_DIR/.git" -prune -o -type l -print -quit)" ] || fail "Checkout contains symlinks; publish from a plain sanitized source checkout."
allowed() {
  case "$1" in */runtime/*|*/build/*|*/qa/*|*/logs/*|*/.git/*|*/.env|*/credentials*|*/activity-history*|*/activity-buckets*|*.jar|*.class|*.dll|*.dylib|*.exe|*.pem|*.key) return 1 ;; esac
  case "$1" in
    README.md|QUICK_START.txt|CHANGELOG.md|ANALYTICS.md|PUBLISHING.md|THIRD_PARTY_NOTICES.txt|LICENSE|LICENSE.txt|.gitignore|.gitattributes|Publish\ Digital\ Marathon.command|Publish\ Digital\ Marathon\ -\ Windows.bat|Start\ Digital\ Marathon\ -\ Windows.bat|Start\ Screen\ Recorder\ -\ Linux.sh|REPAIR_MAC_PERMISSIONS.command) return 0 ;;
    app-files/VERSION|app-files/README.md|app-files/pom.xml|app-files/*.command|app-files/*.bat|app-files/*.sh|app-files/src/*.java|app-files/native/*.c|app-files/native/*.m|app-files/native/*.h|app-files/docs/*.md|app-files/docs/*.txt|app-files/docs/*.pdf|app-files/licenses/*.txt|app-files/scripts/*.sh|app-files/scripts/*.ps1|app-files/scripts/*.rules) return 0 ;;
    app-files/resources/app-icon.png|app-files/resources/app-icon-mac.png|app-files/resources/header-icon.png|app-files/resources/app-icon.ico|app-files/resources/app-icon.icns|app-files/resources/update-feed.json|app-files/resources/analytics-config.json) return 0 ;;
  esac
  return 1
}
private_zip_path() {
  local lower
  lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "/$lower/" in
    */.git/*|*/.gitmodules/*|*/.env*|*/.digital_marathon/*|*/qa/*|*/test-user/*|*/temporary-history/*|*/logs/*|*/history/*|*/userdata/*|*/recordings/*|*/credentials*|*/secrets*|*/activity-buckets.dat/*|*/activity.db/*|*/activity-history*|*/analytics-settings.json/*|*/update-settings.json/*|*/update-preferences.json/*|*/update-session.json/*|*/.update-session-*|*/preferences.json/*|*.log/|*.csv/|*.jpg/|*.jpeg/|*.pem/|*.key/|*.p12/|*.pfx/) return 0 ;;
  esac
  return 1
}
TMP="$(mktemp -d "${TMPDIR:-/tmp}/digital-marathon-publish.XXXXXX")"
# Read/upload a checked temporary copy; do not mutate or depend on changing owner files.
ORIGINAL_ZIP="$ZIP_PATH"
cp "$ZIP_PATH" "$TMP/$ZIP_NAME" || fail "Could not stage the release ZIP."
ZIP_PATH="$TMP/$ZIP_NAME"
if [ -n "$CHECKSUM" ]; then
  cp "$CHECKSUM" "$TMP/$CHECKSUM_NAME" || fail "Could not stage the supplied checksum."
  CHECKSUM="$TMP/$CHECKSUM_NAME"
fi
unzip -tq "$ZIP_PATH" > "$TMP/zip-check.txt" || fail "ZIP integrity check failed."
unzip -Z1 "$ZIP_PATH" > "$TMP/entries.txt"
unzip -Z -l "$ZIP_PATH" | awk '$1 ~ /^l/ {exit 1}' || fail "ZIP symlinks are not permitted."
awk 'seen[tolower($0)]++ {exit 1} $0 !~ /^digital-marathon\// || $0 ~ /(^|\/)\.\.(\/|$)/ || $0 ~ /\\/ || $0 ~ /(^|\/)\.(\/|$)/ || $0 ~ /\/\// || $0 ~ /[[:cntrl:]:*?\[]/ || tolower($0) ~ /(^|\/)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.[^\/]*)?(\/|$)/ {exit 1}' "$TMP/entries.txt" || fail "ZIP must contain one safe digital-marathon root with no duplicate entries."
while IFS= read -r entry; do if private_zip_path "$entry"; then fail "Private or QA entry refused in the release ZIP: $entry"; fi; done < "$TMP/entries.txt"
# A descriptor containing this ZIP's hash cannot be embedded inside that same
# ZIP. Its self-contained generator travels in this script instead; every
# publish regenerates the release asset after validating the complete archive.
if awk 'tolower($0) ~ /(^|\/)digital-marathon-update\.json\/?$/ {found=1} END {exit !found}' "$TMP/entries.txt"; then
  fail "Do not embed digital-marathon-update.json in its own ZIP. The publisher generates it from the verified build."
fi
ACTUAL_SHA="$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')"
if [ -n "$CHECKSUM" ]; then
  EXPECTED_SHA="$(awk -v name="$ZIP_NAME" '$2 == name || $2 == "*" name {print $1}' "$CHECKSUM")"
  [[ "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{64}$ ]] && [ "$ACTUAL_SHA" = "$(printf '%s' "$EXPECTED_SHA" | tr 'A-F' 'a-f')" ] || fail "ZIP SHA-256 does not match its checksum file."
fi
[ "$(unzip -p "$ZIP_PATH" digital-marathon/app-files/VERSION | tr -d '\r\n')" = "$VERSION" ] || fail "ZIP application version mismatch."
unzip -p "$ZIP_PATH" digital-marathon/app-files/app/digital-marathon.jar > "$TMP/app.jar"
unzip -p "$TMP/app.jar" META-INF/MANIFEST.MF | tr -d '\r' | awk -v version="$VERSION" '$0 == "Implementation-Version: " version {found=1} END {exit !found}' || fail "Packaged application JAR version mismatch."
unzip -p "$ZIP_PATH" 'digital-marathon/Digital Marathon.app/Contents/Info.plist' > "$TMP/app.plist"
[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$TMP/app.plist")" = com.girishgupta.inputactivitytracker ] || fail "Wrong Mac product identifier."
[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$TMP/app.plist")" = "$VERSION" ] || fail "Mac application version mismatch."
# Build an isolated allowlisted snapshot from the verified archive, never the live profile.
if [ -z "$SOURCE" ]; then
  SOURCE="$TMP/source"
  mkdir "$SOURCE"
  while IFS= read -r entry; do
    case "$entry" in */) continue ;; esac
    relative="${entry#digital-marathon/}"
    if allowed "$relative"; then
      mkdir -p "$SOURCE/$(dirname "$relative")"
      unzip -p "$ZIP_PATH" "$entry" > "$SOURCE/$relative" || fail "Could not read packaged source: $relative"
      case "$relative" in *.command|*.sh) chmod 755 "$SOURCE/$relative" ;; esac
    fi
  done < "$TMP/entries.txt"
  [ -f "$SOURCE/.gitignore" ] && [ -f "$SOURCE/.gitattributes" ] || fail "Packaged publishing rules are missing. Use the corrected complete ZIP."
fi
SOURCE="$(cd "$SOURCE" && pwd -P)"
[ "$SOURCE" != "$REPO_DIR" ] || fail "Source snapshot and checkout must be separate folders."
case "$SOURCE/" in "$REPO_DIR/"*) fail "Source snapshot must be outside the checkout." ;; esac
case "$REPO_DIR/" in "$SOURCE/"*) fail "Checkout must be outside the source snapshot." ;; esac
[ -z "$(find "$SOURCE" -type l -print -quit)" ] || fail "Source snapshot must not contain symlinks."
[ "$(tr -d '\r\n' < "$SOURCE/app-files/VERSION")" = "$VERSION" ] || fail "Source version mismatch."
[ -f "$SOURCE/app-files/src/com/inputactivitytracker/Main.java" ] || fail "Digital Marathon Main.java is missing."
SOURCE_FILES=()
while IFS= read -r -d '' path; do
  relative="${path#"$SOURCE/"}"
  [[ "$relative" != *$'\n'* && "$relative" != *$'\r'* ]] || fail "Unsupported newline in source filename."
  allowed "$relative" || fail "Non-source or private file refused: $relative"
  case "$relative" in *.java|*.md|*.txt|*.properties|*.json|*.sh|*.ps1|*.command|*.bat)
    if LC_ALL=C grep -Eq '(github_pat_[[:alnum:]_]{20,}|gh[pousr]_[[:alnum:]]{20,}|phx_[[:alnum:]_]{20,}|AKIA[[:alnum:]]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' "$path"; then fail "Possible credential in $relative; remove it before publishing."; fi ;;
  esac
  case "$relative" in .gitignore|.gitattributes|LICENSE|LICENSE.txt) ;;
    *) unzip -p "$ZIP_PATH" "digital-marathon/$relative" | cmp - "$path" >/dev/null || fail "Snapshot differs from verified ZIP: $relative" ;;
  esac
  SOURCE_FILES+=("$relative")
done < <(find "$SOURCE" -type f -print0)
[ "${#SOURCE_FILES[@]}" -ge 5 ] || fail "Source snapshot is incomplete."
# A thin snapshot must never remove valid packaged/tracked source.
# Check both directions: each snapshot file matched above, and every allowed ZIP file here.
while IFS= read -r entry; do
  case "$entry" in */) continue ;; esac
  relative="${entry#digital-marathon/}"
  if allowed "$relative"; then
    [ -f "$SOURCE/$relative" ] || fail "Snapshot is missing packaged source: $relative"
    unzip -p "$ZIP_PATH" "$entry" | cmp - "$SOURCE/$relative" >/dev/null || fail "Packaged source differs from snapshot: $relative"
  fi
done < "$TMP/entries.txt"
TRACKED_FILES=()
while IFS= read -r -d '' relative; do allowed "$relative" || fail "Tracked file outside sanitized scope: $relative"; TRACKED_FILES+=("$relative"); done < <(git -C "$REPO_DIR" ls-files -z)
gh auth status --hostname github.com >/dev/null 2>&1 || fail "Run gh auth login --hostname github.com before publishing."
[ "$(gh api user --hostname github.com --jq .login)" = girishxp ] || fail "Sign in to GitHub CLI on github.com as girishxp."
[ "$(gh repo view "$GITHUB_TARGET" --json nameWithOwner,isPrivate,viewerPermission --jq '[.nameWithOwner, .isPrivate, .viewerPermission] | @tsv')" = $'girishxp/DigitalMarathon\tfalse\tADMIN' ] || fail "A public girishxp/DigitalMarathon repository with owner access is required."
git -C "$REPO_DIR" ls-remote --heads --tags origin > "$TMP/remote-refs.txt"
LOCAL_HEAD="$(git -C "$REPO_DIR" rev-parse --verify HEAD 2>/dev/null || true)"
REMOTE_HEAD="$(awk '$2 == "refs/heads/main" {print $1}' "$TMP/remote-refs.txt")"
[ "$LOCAL_HEAD" = "$REMOTE_HEAD" ] || fail "Local main must exactly match remote main. Sync it before publishing."
if awk -v ref="refs/tags/v$VERSION" '$2 == ref || $2 == ref "^{}" {found=1} END {exit !found}' "$TMP/remote-refs.txt"; then fail "Remote tag v$VERSION already exists. Use a new version."; fi
if git -C "$REPO_DIR" show-ref --verify --quiet "refs/tags/v$VERSION"; then fail "Local tag v$VERSION already exists."; fi
gh api "repos/$GITHUB_REPO/releases" --hostname github.com --paginate --jq '.[].tag_name' > "$TMP/releases.txt"
if grep -Fxq "v$VERSION" "$TMP/releases.txt"; then fail "Release v$VERSION already exists, including a draft. Use a new version."; fi
# Only generate the release checksum after every archive, source and owner check passes.
if [ -z "$CHECKSUM" ]; then
  CHECKSUM="$TMP/$CHECKSUM_NAME"
  printf '%s  %s\n' "$ACTUAL_SHA" "$ZIP_NAME" > "$CHECKSUM"
  printf '\nChecksum generated from the checked ZIP; no separate download is required.\n'
fi
# The uploaded descriptor and GitHub release use exactly the same notes. JSON
# strings are serialized locally rather than interpolated into JSON or shell.
printf 'Digital Marathon v%s\n\nDownload the combined Mac/Windows package, extract it and open the launcher for your computer.\n\nSHA-256: `%s`\n\nSource and owner publishing instructions are included.\n' "$VERSION" "$ACTUAL_SHA" > "$TMP/release-notes.md"
FEED="$TMP/$FEED_NAME"
python3 - "$ZIP_PATH" "$ACTUAL_SHA" "$VERSION" "$ZIP_NAME" "$TMP/release-notes.md" "$FEED" <<'FEED_PY'
from pathlib import Path
import hashlib, json, re, sys
archive, expected, version, name, notes_path, destination = sys.argv[1:]
archive = Path(archive)
if not re.fullmatch(r'[0-9a-f]{64}', expected): raise SystemExit('Invalid checked ZIP digest for update descriptor.')
if archive.name != name or name != f'digital-marathon-cross-platform-v{version}-click-to-launch.zip':
    raise SystemExit('Update descriptor ZIP name/version mismatch.')
size = archive.stat().st_size
if size <= 0 or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit('Checked ZIP changed before update descriptor generation.')
notes = Path(notes_path).read_text(encoding='utf-8')
if len(notes) > 6000: raise SystemExit('Release notes must be at most 6000 characters for the update descriptor.')
payload = {'schema': 1, 'app': 'digital-marathon', 'version': version, 'notes': notes,
           'asset': {'name': name, 'bytes': size, 'sha256': expected}}
Path(destination).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
FEED_PY
FEED_SHA="$(shasum -a 256 "$FEED" | awk '{print $1}')"
printf '\nVerified Digital Marathon %s\nRepository: %s\nCheckout: %s\nSource files: %s\nZIP: %s\nSHA-256: %s\n' "$VERSION" "$GITHUB_REPO" "$REPO_DIR" "${#SOURCE_FILES[@]}" "$ORIGINAL_ZIP" "$ACTUAL_SHA"
printf 'Update descriptor generated from the verified ZIP; no separate download is required.\n'
if [ "$DRY_RUN" -eq 1 ]; then printf '\nDRY RUN PASSED. No checkout, tag, GitHub release or analytics data changed.\n'; exit 0; fi
printf '\nPublish this verified source and package to GitHub? [y/N] '
IFS= read -r answer
case "$answer" in y|Y|yes|YES) ;; *) printf 'Cancelled. Nothing changed.\n'; exit 0 ;; esac
# Only validated managed source files are copied/deleted. No reset --hard or clean is used.
# macOS Bash 3.2 treats an empty array as unset under nounset; preserve zero iterations safely.
for relative in ${TRACKED_FILES[@]+"${TRACKED_FILES[@]}"}; do if [ ! -f "$SOURCE/$relative" ]; then rm -f "$REPO_DIR/$relative"; fi; done
for relative in ${SOURCE_FILES[@]+"${SOURCE_FILES[@]}"}; do mkdir -p "$REPO_DIR/$(dirname "$relative")"; cp -p "$SOURCE/$relative" "$REPO_DIR/$relative"; done
git -C "$REPO_DIR" add --all -- .
if ! git -C "$REPO_DIR" diff --cached --quiet; then git -C "$REPO_DIR" commit -m "Digital Marathon v$VERSION"; fi
COMMIT="$(git -C "$REPO_DIR" rev-parse HEAD)"
git -C "$REPO_DIR" push origin "$BRANCH"
git -C "$REPO_DIR" tag -a "v$VERSION" "$COMMIT" -m "Digital Marathon v$VERSION"
git -C "$REPO_DIR" push origin "refs/tags/v$VERSION"
git -C "$REPO_DIR" ls-remote origin "refs/tags/v$VERSION" "refs/tags/v$VERSION^{}" > "$TMP/published-tag.txt"
TAG_COMMIT="$(awk -v ref="refs/tags/v$VERSION^{}" '$2 == ref {print $1}' "$TMP/published-tag.txt")"
[ "$TAG_COMMIT" = "$COMMIT" ] || fail "Remote release tag does not resolve to the reviewed commit. No release was published."
gh release create "v$VERSION" --repo "$GITHUB_TARGET" --target "$COMMIT" --verify-tag --title "Digital Marathon v$VERSION" --notes-file "$TMP/release-notes.md" --draft --latest=false
gh release upload "v$VERSION" "$ZIP_PATH" "$CHECKSUM" "$FEED" --repo "$GITHUB_TARGET"
mkdir "$TMP/remote-assets"
gh release download "v$VERSION" --repo "$GITHUB_TARGET" --pattern "$ZIP_NAME" --pattern "$CHECKSUM_NAME" --pattern "$FEED_NAME" --dir "$TMP/remote-assets"
[ "$(shasum -a 256 "$TMP/remote-assets/$ZIP_NAME" | awk '{print $1}')" = "$ACTUAL_SHA" ] || fail "Uploaded ZIP failed verification. Release remains a draft; inspect it manually."
cmp "$CHECKSUM" "$TMP/remote-assets/$CHECKSUM_NAME" >/dev/null || fail "Uploaded checksum failed verification. Release remains a draft."
cmp "$FEED" "$TMP/remote-assets/$FEED_NAME" >/dev/null && [ "$(shasum -a 256 "$TMP/remote-assets/$FEED_NAME" | awk '{print $1}')" = "$FEED_SHA" ] || fail "Uploaded update descriptor failed verification. Release remains a draft."
[ "$(gh release view "v$VERSION" --repo "$GITHUB_TARGET" --json assets --jq '.assets | length')" = 3 ] || fail "Unexpected draft assets. Inspect the draft before publication."
git -C "$REPO_DIR" ls-remote origin "refs/tags/v$VERSION^{}" > "$TMP/verified-tag.txt"
[ "$(awk '{print $1}' "$TMP/verified-tag.txt")" = "$COMMIT" ] || fail "Release tag changed during upload. The release remains a draft."
gh release edit "v$VERSION" --repo "$GITHUB_TARGET" --draft=false --latest
[ "$(gh api "repos/$GITHUB_REPO/releases/latest" --hostname github.com --jq .tag_name)" = "v$VERSION" ] || fail "Release was published, but Latest verification failed. Inspect GitHub manually."
printf '\nPublished and verified: https://github.com/%s/releases/tag/v%s\n' "$GITHUB_REPO" "$VERSION"
