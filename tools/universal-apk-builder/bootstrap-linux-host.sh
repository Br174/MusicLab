#!/usr/bin/env bash
set -euo pipefail

UAB_HOME="${UAB_HOME:-$HOME/.uab}"
ANDROID_HOME="${ANDROID_HOME:-$UAB_HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="${UAB_CMDLINE_TOOLS_VERSION:-13114758}"
ANDROID_PLATFORM="${UAB_ANDROID_PLATFORM:-android-36}"
ANDROID_BUILD_TOOLS="${UAB_ANDROID_BUILD_TOOLS:-36.0.0}"

say(){ printf '[UAB-BOOTSTRAP] %s\n' "$*"; }
fail(){ printf '[UAB-BOOTSTRAP][ERROR] %s\n' "$*" >&2; exit 1; }

if command -v apt-get >/dev/null 2>&1; then
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    SUDO=""
  elif command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  else
    fail "Su Debian/Ubuntu servono privilegi sudo per installare i pacchetti di base."
  fi
  say "Controllo pacchetti di base"
  $SUDO apt-get update -y
  $SUDO apt-get install -y ca-certificates curl git unzip zip openjdk-21-jdk
else
  command -v java >/dev/null 2>&1 || fail "JDK non trovato. Installa JDK 21 e rilancia lo script."
  command -v curl >/dev/null 2>&1 || fail "curl non trovato."
  command -v unzip >/dev/null 2>&1 || fail "unzip non trovato."
  command -v zip >/dev/null 2>&1 || fail "zip non trovato."
fi

mkdir -p "$UAB_HOME" "$ANDROID_HOME/cmdline-tools"

if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  say "Installazione Android command-line tools"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o "$TMP_DIR/tools.zip"
  unzip -q "$TMP_DIR/tools.zip" -d "$TMP_DIR/unpacked"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$TMP_DIR/unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS:$PATH"

say "Accettazione licenze Android SDK"
yes | sdkmanager --licenses >/dev/null || true
say "Installazione SDK $ANDROID_PLATFORM e build-tools $ANDROID_BUILD_TOOLS"
sdkmanager "platform-tools" "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS"

cat > "$UAB_HOME/env.sh" <<EOF
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS:\$PATH"
EOF

say "Ambiente pronto"
say "Java: $(java -version 2>&1 | head -n1)"
say "Android SDK: $ANDROID_HOME"
say "File ambiente: $UAB_HOME/env.sh"
say "Il progetto userà il proprio Gradle Wrapper: non serve installare Gradle globalmente."
