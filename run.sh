#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# run.sh — One-button build (and optional install) for the Android app
# Usage:
#   ./run.sh [--install] [--mode debug|release] [--no-clean] [--sdk-setup] [--open]
#
# Examples:
#   ./run.sh                       # clean + assembleDebug
#   ./run.sh --install             # build debug APK and install to connected device
#   ./run.sh --mode release        # assembleRelease (requires signing in Gradle)
#   ./run.sh --no-clean --install  # faster incremental build + install
#   ./run.sh --sdk-setup           # accept licenses, ensure platform/build-tools
#   ./run.sh --open                # after build, open the folder containing the APK
# -----------------------------------------------------------------------------
set -Eeuo pipefail

# --------------- tiny logger helpers ---------------
log(){ printf "\033[1;34m[INFO]\033[0m %s\n" "$*"; }
warn(){ printf "\033[1;33m[WARN]\033[0m %s\n" "$*"; }
err(){ printf "\033[1;31m[ERR ]\033[0m %s\n" "$*"; }
die(){ err "$*"; exit 1; }

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$ROOT_DIR"

# --------------- defaults / args ---------------
DO_INSTALL=0
MODE="debug"     # debug | release
DO_CLEAN=1
DO_SDK_SETUP=0
DO_OPEN=0        # open folder containing APK after build

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install)   DO_INSTALL=1; shift ;;
    --mode)      MODE="${2:-debug}"; shift 2 ;;
    --no-clean)  DO_CLEAN=0; shift ;;
    --sdk-setup) DO_SDK_SETUP=1; shift ;;
    --open)      DO_OPEN=1; shift ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | sed 's/^#\ ?//'
      exit 0
      ;;
    *)
      warn "Unknown arg: $1"; shift ;;
  esac
done

if [[ "$MODE" != "debug" && "$MODE" != "release" ]]; then
  die "--mode must be 'debug' or 'release'"
fi

# --------------- JDK detection (Android Studio JBR first) ---------------
pick_jdk(){
  local cands=(
    "/usr/local/android-studio/jbr"
    "/opt/android-studio/jbr"
    "$HOME/android-studio/jbr"
    "/snap/android-studio/current/android-studio/jbr"
    "/snap/android-studio/current/jbr"
    "/usr/lib/jvm/java-17-openjdk-amd64"
    "/usr/lib/jvm/java-17-openjdk"
  )
  for d in "${cands[@]}"; do
    if [[ -x "$d/bin/java" ]]; then
      echo "$d"; return 0
    fi
  done
  # fallback to existing JAVA_HOME if valid
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    echo "$JAVA_HOME"; return 0
  fi
  return 1
}

if JDK_DIR="$(pick_jdk)"; then
  export JAVA_HOME="$JDK_DIR"
  export PATH="$JAVA_HOME/bin:$PATH"
  log "Using JDK: $JAVA_HOME"
else
  warn "JDK 17 not found; relying on system java in PATH"
  command -v java >/dev/null || die "No java found. Install OpenJDK 17 or Android Studio."
fi

# --------------- Android SDK env ---------------
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Prefer newest cmdline-tools if present
SDKMANAGER_BIN=""
if [[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  SDKMANAGER_BIN="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
elif [[ -x "$ANDROID_HOME/cmdline-tools/bin/sdkmanager" ]]; then
  SDKMANAGER_BIN="$ANDROID_HOME/cmdline-tools/bin/sdkmanager"
else
  cand=$(find "$ANDROID_HOME/cmdline-tools" -maxdepth 2 -type f -name sdkmanager 2>/dev/null | head -n1 || true)
  [[ -n "$cand" ]] && SDKMANAGER_BIN="$cand"
fi

export PATH="$ANDROID_HOME/platform-tools:$PATH"
[[ -n "$SDKMANAGER_BIN" ]] && export PATH="$(dirname "$SDKMANAGER_BIN"):$PATH"

# --------------- optional SDK setup ---------------
if (( DO_SDK_SETUP )); then
  if [[ -z "$SDKMANAGER_BIN" ]]; then
    warn "sdkmanager not found under $ANDROID_HOME/cmdline-tools/*."
    warn "Install Android Studio cmdline-tools from the SDK Manager or:"
    warn "  mkdir -p \"$ANDROID_HOME/cmdline-tools\" && cd \"$ANDROID_HOME/cmdline-tools\""
    warn "  # download 'commandlinetools-linux-*.zip' from developer.android.com"
    warn "  unzip commandlinetools-*.zip -d latest"
  else
    log "Accepting SDK licenses..."
    yes | "$SDKMANAGER_BIN" --licenses >/dev/null || true
    log "Ensuring platform-tools, platform 34, build-tools 34.0.0..."
    "$SDKMANAGER_BIN" "platform-tools" "platforms;android-34" "build-tools;34.0.0" || warn "sdkmanager reported a non-fatal issue."
  fi
fi

# --------------- Gradle wrapper ---------------
if [[ ! -x "./gradlew" ]]; then
  if command -v gradle >/dev/null 2>&1; then
    log "Creating Gradle wrapper (8.6)..."
    gradle wrapper --gradle-version 8.6
  else
    die "gradlew not found and 'gradle' CLI is not installed. Install Gradle or open project once in Android Studio to generate wrapper."
  fi
fi
chmod +x gradlew

# --------------- Build ---------------
if (( DO_CLEAN )); then
  log "Cleaning project..."
  ./gradlew --no-daemon clean
fi

TASK=":app:assembleDebug"
APK_PATH_REL="app/build/outputs/apk/debug/app-debug.apk"
if [[ "$MODE" == "release" ]]; then
  TASK=":app:assembleRelease"
  APK_PATH_REL="app/build/outputs/apk/release/app-release.apk"
fi

log "Building ($MODE)…"
./gradlew --no-daemon "$TASK"

[[ -f "$APK_PATH_REL" ]] || die "Expected APK not found: $APK_PATH_REL"

# Absolute path + containing directory
# Prefer readlink -f. If not available, use python as a portable fallback.
abs_path(){
  local p="$1"
  if command -v readlink >/dev/null 2>&1; then
    readlink -f "$p" 2>/dev/null || python3 - <<'PY'
import os,sys
print(os.path.abspath(sys.argv[1]))
PY
  else
    python3 - <<'PY'
import os,sys
print(os.path.abspath(sys.argv[1]))
PY
  fi
}

APK_PATH_ABS="$(abs_path "$APK_PATH_REL")"
APK_DIR="$(dirname "$APK_PATH_ABS")"

log "APK ready:"
printf "  \033[1m%s\033[0m\n" "$APK_PATH_ABS"

# Helpful extra info
if command -v sha256sum >/dev/null 2>&1; then
  SHA="$(sha256sum "$APK_PATH_ABS" | awk '{print $1}')"
  log "SHA256: $SHA"
fi

# --------------- Optional open folder ---------------
if (( DO_OPEN )); then
  if command -v xdg-open >/dev/null 2>&1; then
    log "Opening folder: $APK_DIR"
    (xdg-open "$APK_DIR" >/dev/null 2>&1 &) || warn "xdg-open failed (headless shell?)."
  else
    warn "xdg-open not found; cannot open folder automatically."
  fi
fi

# --------------- Optional install ---------------
if (( DO_INSTALL )); then
  command -v adb >/dev/null 2>&1 || die "adb not found. Install Android platform-tools."
  adb start-server >/dev/null || true
  DEV_LINE="$(adb devices | awk '/\tdevice$/{print $1}' | head -n1)"
  if [[ -z "$DEV_LINE" ]]; then
    die "No connected device. Run 'adb devices' and ensure your phone is authorized."
  fi
  log "Installing to device: $DEV_LINE"
  adb -s "$DEV_LINE" install -r "$APK_PATH_ABS" || die "adb install failed."
  log "Install complete."
fi

log "Done."

