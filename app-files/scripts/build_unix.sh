#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:-}"
MODE="${2:-run}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '\r\n' < "$ROOT/VERSION")"
HOOK_VERSION="2.2.2"
HOOK_JAR="$ROOT/lib/jnativehook-$HOOK_VERSION.jar"
HOOK_URL="https://repo.maven.apache.org/maven2/com/github/kwhat/jnativehook/$HOOK_VERSION/jnativehook-$HOOK_VERSION.jar"
HOOK_FALLBACK_URL="https://downloads.sourceforge.net/project/jnativehook.mirror/$HOOK_VERSION/jnativehook-$HOOK_VERSION.jar"
CACHE="$ROOT/.build-tools"
INPUT="$ROOT/build/package-input"
LOG="$ROOT/digital-marathon-startup.log"

exec > >(tee "$LOG") 2>&1

case "$PLATFORM" in
  mac|linux) ;;
  *) echo "Usage: build_unix.sh mac|linux [run|clean]"; exit 2 ;;
esac

DEST="$ROOT/build/$PLATFORM"
STAMP="$DEST/.digital-marathon-version"
if [[ "$PLATFORM" == "mac" ]]; then
  APP="$DEST/Digital Marathon.app"
else
  APP="$DEST/Digital Marathon"
fi

launch_existing() {
  if [[ "$PLATFORM" == "mac" ]]; then
    echo "Opening $APP"
    open "$APP"
  else
    local executable="$APP/bin/Digital Marathon"
    if [[ ! -x "$executable" ]]; then return 1; fi
    echo "Opening $executable"
    nohup "$executable" >/dev/null 2>&1 &
  fi
}

if [[ "$MODE" == "clean" ]]; then
  if [[ "$PLATFORM" == "mac" ]]; then
    osascript -e 'tell application "Digital Marathon" to quit' >/dev/null 2>&1 || true
    sleep 1
  fi
  rm -rf "$DEST" "$INPUT"
elif [[ -e "$APP" && -f "$STAMP" && "$(tr -d '\r\n' < "$STAMP")" == "$VERSION" ]]; then
  launch_existing
  exit 0
elif [[ -e "$APP" ]]; then
  rm -rf "$DEST"
fi

mkdir -p "$ROOT/lib" "$ROOT/build" "$CACHE"

need_command() {
  command -v "$1" >/dev/null 2>&1
}

download_file() {
  local url="$1"
  local target="$2"
  local temp="$target.part"
  echo "Downloading $(basename "$target")..."
  rm -f "$temp"
  if need_command curl; then
    if ! curl -fL --retry 3 --connect-timeout 20 "$url" -o "$temp"; then
      rm -f "$temp"
      return 1
    fi
  elif need_command wget; then
    if ! wget --tries=3 --timeout=20 -O "$temp" "$url"; then
      rm -f "$temp"
      return 1
    fi
  else
    echo "curl or wget is required for the one-time runtime download."
    exit 1
  fi
  mv "$temp" "$target"
}

# Windows and Linux use JNativeHook. macOS uses the included native Quartz backend
# and intentionally does not package JNativeHook's legacy assistive-device check.
if [[ "$PLATFORM" == "linux" ]]; then
  if [[ ! -s "$HOOK_JAR" ]] || [[ "$(wc -c < "$HOOK_JAR" | tr -d ' ')" -lt 600000 ]]; then
    rm -f "$HOOK_JAR"
    if ! download_file "$HOOK_URL" "$HOOK_JAR"; then
      rm -f "$HOOK_JAR" "$HOOK_JAR.part"
      download_file "$HOOK_FALLBACK_URL" "$HOOK_JAR"
    fi
  fi
fi

jpackage_is_usable() {
  local candidate="$1"
  local version major
  [[ -x "$candidate" ]] || return 1
  version="$("$candidate" --version 2>/dev/null | head -n 1 || true)"
  if [[ "$version" =~ ^([0-9]+) ]]; then
    major="${BASH_REMATCH[1]}"
    (( major >= 21 ))
    return
  fi
  return 1
}

JPACKAGE=""
if need_command jpackage; then
  CANDIDATE_JPACKAGE="$(command -v jpackage)"
  if jpackage_is_usable "$CANDIDATE_JPACKAGE"; then
    JPACKAGE="$CANDIDATE_JPACKAGE"
  fi
fi

if [[ -z "$JPACKAGE" ]]; then
  ARCH_RAW="$(uname -m)"
  case "$ARCH_RAW" in
    arm64|aarch64) ARCH="aarch64" ;;
    x86_64|amd64) ARCH="x64" ;;
    *) echo "Unsupported CPU architecture: $ARCH_RAW"; exit 1 ;;
  esac

  JDK_DIR="$CACHE/jdk-21-$PLATFORM-$ARCH"
  if [[ "$PLATFORM" == "mac" ]]; then
    JPACKAGE="$JDK_DIR/Contents/Home/bin/jpackage"
    JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/mac/$ARCH/jdk/hotspot/normal/eclipse?project=jdk"
  else
    JPACKAGE="$JDK_DIR/bin/jpackage"
    JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/$ARCH/jdk/hotspot/normal/eclipse?project=jdk"
  fi

  if [[ ! -x "$JPACKAGE" ]]; then
    ARCHIVE="$CACHE/jdk-21-$PLATFORM-$ARCH.tar.gz"
    rm -rf "$JDK_DIR"
    download_file "$JDK_URL" "$ARCHIVE"
    mkdir -p "$JDK_DIR"
    tar -xzf "$ARCHIVE" --strip-components=1 -C "$JDK_DIR"
    rm -f "$ARCHIVE"
  fi
fi

if [[ ! -x "$JPACKAGE" ]]; then
  echo "A Java 21+ JDK with jpackage could not be prepared."
  exit 1
fi

if need_command realpath; then
  JPACKAGE_REAL="$(realpath "$JPACKAGE")"
else
  JPACKAGE_REAL="$(cd "$(dirname "$JPACKAGE")" && pwd)/$(basename "$JPACKAGE")"
fi
JDK_HOME="$(cd "$(dirname "$JPACKAGE_REAL")/.." && pwd)"

APP_JAR="$ROOT/app/digital-marathon.jar"
if [[ ! -s "$APP_JAR" ]]; then
  echo "Missing application JAR: $APP_JAR"
  exit 1
fi

rm -rf "$INPUT"
mkdir -p "$INPUT"
cp "$APP_JAR" "$INPUT/digital-marathon.jar"

if [[ "$PLATFORM" == "linux" ]]; then
  cp "$HOOK_JAR" "$INPUT/jnativehook-$HOOK_VERSION.jar"
  ICON="$ROOT/resources/app-icon.png"
else
  ICON="$ROOT/resources/app-icon.icns"
  MAC_SOURCE="$ROOT/native/macos/MacInputHook.c"
  MAC_WINDOW_SOURCE="$ROOT/native/macos/MacWindowSupport.m"
  MAC_LIBRARY="$INPUT/libInputActivityMacHook.dylib"
  if [[ ! -f "$MAC_SOURCE" ]]; then
    echo "Missing macOS native input source: $MAC_SOURCE"
    exit 1
  fi
  if [[ ! -f "$MAC_WINDOW_SOURCE" ]]; then
    echo "Missing macOS native window source: $MAC_WINDOW_SOURCE"
    exit 1
  fi
  if ! need_command xcrun; then
    echo "Apple Command Line Tools are required. Run: xcode-select --install"
    exit 1
  fi
  SDKROOT="$(xcrun --sdk macosx --show-sdk-path 2>/dev/null || true)"
  CLANG="$(xcrun --sdk macosx --find clang 2>/dev/null || true)"
  if [[ -z "$SDKROOT" || ! -d "$SDKROOT" ]]; then
    echo "The macOS SDK could not be located. Run: xcode-select --install"
    exit 1
  fi
  if [[ -z "$CLANG" || ! -x "$CLANG" ]]; then
    echo "Apple clang was not found. Run: xcode-select --install"
    exit 1
  fi
  if ! printf '#define _DARWIN_C_SOURCE 1\n#include <sys/types.h>\n#include <CoreGraphics/CoreGraphics.h>\n#import <AppKit/AppKit.h>\nint main(void) { return 0; }\n' | \
       "$CLANG" -isysroot "$SDKROOT" -F"$SDKROOT/System/Library/Frameworks" \
       -x objective-c -std=gnu11 -fsyntax-only - >/dev/null 2>&1; then
    echo "The selected Apple developer tools cannot compile the required CoreGraphics/AppKit SDK headers."
    echo "Selected developer directory: $(xcode-select -p 2>/dev/null || echo unknown)"
    echo "Selected SDK: $SDKROOT"
    echo "Run 'xcode-select --install', or select a complete Xcode installation with:"
    echo "  sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer"
    exit 1
  fi
  echo "Building the native macOS keyboard listener with SDK: $SDKROOT"
  "$CLANG" -x objective-c -dynamiclib -O2 -std=gnu11 -fPIC -fblocks -pthread -mmacosx-version-min=10.15 \
    -isysroot "$SDKROOT" -F"$SDKROOT/System/Library/Frameworks" \
    -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" \
    "$MAC_SOURCE" "$MAC_WINDOW_SOURCE" \
    -framework CoreGraphics -framework CoreFoundation -framework AppKit \
    -o "$MAC_LIBRARY"
fi

if [[ ! -e "$APP" ]]; then
  rm -rf "$DEST"
  mkdir -p "$DEST"
  echo "Building Digital Marathon $VERSION for $PLATFORM..."
  ARGS=(
    --type app-image
    --name "Digital Marathon"
    --dest "$DEST"
    --input "$INPUT"
    --main-jar digital-marathon.jar
    --main-class com.inputactivitytracker.Main
    --app-version "$VERSION"
    --vendor "Girish Gupta"
    --description "Live mouse distance and keyboard press counter"
    --icon "$ICON"
    --java-options "-Dfile.encoding=UTF-8"
  )
  if [[ "$PLATFORM" == "mac" ]]; then
    ARGS+=(--mac-package-identifier com.girishgupta.inputactivitytracker)
    ARGS+=(--mac-package-name "Digital Marathon")
    ARGS+=(--java-options "-Dapple.awt.application.appearance=system")
    ARGS+=(--java-options '-Djava.library.path=$APPDIR')
    # Native AppKit applies NSWindow alpha while keeping the macOS title bar.
    # The peer openings remain as a compatibility fallback on older JDK builds.
    ARGS+=(--java-options "--add-opens=java.desktop/java.awt=ALL-UNNAMED")
    ARGS+=(--java-options "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED")
    ARGS+=(--java-options "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
  else
    # Linux X11 peers can also apply native opacity to a decorated window.
    ARGS+=(--java-options "--add-opens=java.desktop/java.awt=ALL-UNNAMED")
    ARGS+=(--java-options "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
  fi
  "$JPACKAGE" "${ARGS[@]}"
  if [[ "$PLATFORM" == "mac" ]] && need_command codesign; then
    # Keep a consistent bundle identifier and apply a valid local ad-hoc signature.
    # The included repair script clears a stale TCC record after future rebuilds.
    codesign --force --deep --sign - --identifier com.girishgupta.inputactivitytracker "$APP"
  fi
  printf '%s\n' "$VERSION" > "$STAMP"
fi

if [[ "$PLATFORM" == "mac" ]]; then
  xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true
fi
if ! launch_existing; then
  echo "Built application executable was not found under: $APP"
  exit 1
fi
