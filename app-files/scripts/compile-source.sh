#!/usr/bin/env bash
# Compile the source checkout only. This does not launch or replace a Mac app.
set -euo pipefail

COMPILE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPILE_VERSION="$(tr -d '\r\n' < "$COMPILE_ROOT/VERSION")"
HOOK_VERSION="2.2.2"
HOOK_SHA256="2c7904423bc680af02d9ea9557ae233c35199e302d072773a9d0304b568acd41"
HOOK_JAR="$COMPILE_ROOT/lib/jnativehook-$HOOK_VERSION.jar"
HOOK_URL="https://repo.maven.apache.org/maven2/com/github/kwhat/jnativehook/$HOOK_VERSION/jnativehook-$HOOK_VERSION.jar"
BUILD_WORK=""
HOOK_PART=""

fail() { printf 'Source compilation failed: %s\n' "$*" >&2; exit 1; }
cleanup() {
  [[ -z "$HOOK_PART" ]] || rm -f "$HOOK_PART"
  [[ -z "$BUILD_WORK" ]] || rm -rf "$BUILD_WORK"
}
trap cleanup EXIT

[[ "$COMPILE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "VERSION must contain a semantic version."
[[ -d "$COMPILE_ROOT/src" ]] || fail "The src directory is missing."
[[ -d "$COMPILE_ROOT/resources" ]] || fail "The resources directory is missing."
[[ -s "$COMPILE_ROOT/docs/Digital-Marathon-Product-Guide.pdf" ]] || fail "The product-guide PDF is missing."

if [[ -n "${JAVA_HOME:-}" ]]; then
  COMPILE_JAVAC="$JAVA_HOME/bin/javac"
  COMPILE_JAR="$JAVA_HOME/bin/jar"
else
  COMPILE_JAVAC="$(command -v javac || true)"
  COMPILE_JAR="$(command -v jar || true)"
fi
[[ -n "$COMPILE_JAVAC" && -x "$COMPILE_JAVAC" ]] || fail "Install a Java 21 JDK and set JAVA_HOME or PATH."
[[ -n "$COMPILE_JAR" && -x "$COMPILE_JAR" ]] || fail "The Java 21 jar tool is missing."
JAVAC_VERSION="$("$COMPILE_JAVAC" -version 2>&1)"
JAR_VERSION="$("$COMPILE_JAR" --version 2>&1)"
[[ "$JAVAC_VERSION" =~ ^javac[[:space:]]+21([.]|[[:space:]]|$) ]] || fail "Java 21 is required because the Windows code uses its preview API."
[[ "$JAR_VERSION" =~ ^jar[[:space:]]+21([.]|[[:space:]]|$) ]] || fail "The jar and javac tools must both come from Java 21."

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else fail "A SHA-256 tool (shasum or sha256sum) is required."
  fi
}

mkdir -p "$COMPILE_ROOT/lib" "$COMPILE_ROOT/build" "$COMPILE_ROOT/app"
if [[ ! -f "$HOOK_JAR" ]]; then
  HOOK_PART="$(mktemp "$COMPILE_ROOT/lib/.jnativehook-download.XXXXXX")"
  printf 'Downloading the pinned JNativeHook %s dependency from Maven Central...\n' "$HOOK_VERSION"
  if command -v curl >/dev/null 2>&1; then
    curl --proto '=https' --proto-redir '=https' --fail --location --retry 2 \
      --connect-timeout 10 --max-time 120 --max-filesize 10485760 "$HOOK_URL" -o "$HOOK_PART"
  elif command -v wget >/dev/null 2>&1; then
    wget --https-only --timeout=20 --tries=2 -O "$HOOK_PART" "$HOOK_URL"
  else fail "curl or wget is required to fetch the missing dependency."
  fi
  [[ "$(sha256_file "$HOOK_PART")" == "$HOOK_SHA256" ]] || fail "The dependency SHA-256 does not match the pinned release."
  mv "$HOOK_PART" "$HOOK_JAR"
  HOOK_PART=""
fi
[[ "$(sha256_file "$HOOK_JAR")" == "$HOOK_SHA256" ]] || fail "The existing JNativeHook dependency does not match the pinned SHA-256."

BUILD_WORK="$(mktemp -d "$COMPILE_ROOT/build/source-compile.XXXXXX")"
mkdir -p "$BUILD_WORK/classes" "$BUILD_WORK/stage"
SOURCES="$BUILD_WORK/sources.txt"
cd "$COMPILE_ROOT"
find src -type f -name '*.java' -print | LC_ALL=C sort | while IFS= read -r SOURCE; do
  SOURCE="${SOURCE//\\/\\\\}"
  SOURCE="${SOURCE//\"/\\\"}"
  printf '"%s"\n' "$SOURCE"
done > "$SOURCES"
[[ -s "$SOURCES" ]] || fail "No Java sources were found."
printf 'Compiling Digital Marathon %s with Java 21...\n' "$COMPILE_VERSION"
"$COMPILE_JAVAC" --release 21 --enable-preview -encoding UTF-8 -cp "$HOOK_JAR" \
  -d "$BUILD_WORK/classes" "@$SOURCES"
cp -R "$BUILD_WORK/classes/." "$BUILD_WORK/stage/"
cp -R "$COMPILE_ROOT/resources/." "$BUILD_WORK/stage/"
cp "$COMPILE_ROOT/docs/Digital-Marathon-Product-Guide.pdf" "$BUILD_WORK/stage/Digital-Marathon-Product-Guide.pdf"
printf 'Manifest-Version: 1.0\nMain-Class: com.inputactivitytracker.Main\nImplementation-Title: Digital Marathon\nImplementation-Version: %s\n\n' \
  "$COMPILE_VERSION" > "$BUILD_WORK/manifest.mf"
"$COMPILE_JAR" --create --file "$BUILD_WORK/digital-marathon.jar" \
  --manifest "$BUILD_WORK/manifest.mf" -C "$BUILD_WORK/stage" .
mv "$BUILD_WORK/digital-marathon.jar" "$COMPILE_ROOT/app/digital-marathon.jar"
printf 'Built %s\n' "$COMPILE_ROOT/app/digital-marathon.jar"
