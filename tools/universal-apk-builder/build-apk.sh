#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
CONFIG_FILE="${UAB_CONFIG_FILE:-$PROJECT_ROOT/uab-project.env}"
OUTPUT_DIR="${UAB_OUTPUT_DIR:-$PROJECT_ROOT/dist/uab}"
LOG_DIR="$OUTPUT_DIR/logs"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="$LOG_DIR/build-$TIMESTAMP.log"

mkdir -p "$LOG_DIR"

if [[ -f "$CONFIG_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
fi

status() { printf '[UAB] %s\n' "$*"; }
fail() { printf '[UAB][ERROR] %s\n' "$*" >&2; exit "${2:-1}"; }

[[ -f "$PROJECT_ROOT/gradlew" ]] || fail "gradlew non trovato: il progetto non sembra un progetto Gradle Android standard." 10
chmod +x "$PROJECT_ROOT/gradlew"

command -v java >/dev/null 2>&1 || fail "Java/JDK non disponibile." 11
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
[[ -n "$JAVA_MAJOR" ]] || fail "Impossibile determinare la versione Java." 11
if (( JAVA_MAJOR < 17 )); then
  fail "JDK troppo vecchio: trovato Java $JAVA_MAJOR. Serve almeno Java 17; per MusicLab è richiesto Java 21." 12
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || fail "Android SDK non disponibile. Avviare il Builder tramite run-container.sh oppure configurare ANDROID_SDK_ROOT." 13

TASK="${UAB_TASK:-}"
if [[ -z "$TASK" ]]; then
  APP_MODULE=""
  while IFS= read -r gradle_file; do
    if grep -Eq 'com\.android\.application' "$gradle_file"; then
      rel="${gradle_file#$PROJECT_ROOT/}"
      APP_MODULE="${rel%%/*}"
      [[ "$APP_MODULE" == "$rel" ]] && APP_MODULE=""
      [[ -n "$APP_MODULE" ]] && break
    fi
  done < <(find "$PROJECT_ROOT" -maxdepth 3 \( -name build.gradle -o -name build.gradle.kts \) -type f | sort)
  [[ -n "$APP_MODULE" ]] || fail "Modulo Android application non rilevato automaticamente. Impostare UAB_TASK in uab-project.env." 14
  TASK=":$APP_MODULE:assembleDebug"
fi

status "Progetto: $PROJECT_ROOT"
status "Java: $JAVA_MAJOR"
status "Android SDK: $SDK_ROOT"
status "Task: $TASK"
status "Output: $OUTPUT_DIR"

cd "$PROJECT_ROOT"
set +e
# UAB_GRADLE_ARGS is intentionally word-split to support multiple optional Gradle flags.
./gradlew "$TASK" --console=plain --warning-mode summary ${UAB_GRADLE_ARGS:-} 2>&1 | tee "$LOG_FILE"
BUILD_RC=${PIPESTATUS[0]}
set -e

if (( BUILD_RC != 0 )); then
  REASON="errore Gradle non classificato"
  grep -Eqi 'SDK location not found|ANDROID_HOME|ANDROID_SDK_ROOT' "$LOG_FILE" && REASON="Android SDK non configurato"
  grep -Eqi 'Could not resolve|Could not GET|Could not HEAD|UnknownHostException|Connection timed out' "$LOG_FILE" && REASON="dipendenza o rete non disponibile"
  grep -Eqi 'Compilation error|e: file:|error: ' "$LOG_FILE" && REASON="errore di compilazione nel codice"
  grep -Eqi 'Keystore|signing|storeFile|validateSigning' "$LOG_FILE" && REASON="problema di firma/keystore"
  printf '[UAB][FAILED] %s\n[UAB][LOG] %s\n' "$REASON" "$LOG_FILE" >&2
  exit "$BUILD_RC"
fi

rm -rf "$OUTPUT_DIR/apk"
mkdir -p "$OUTPUT_DIR/apk"

mapfile -t APKS < <(
  if [[ -n "${UAB_APK_GLOB:-}" ]]; then
    compgen -G "$PROJECT_ROOT/$UAB_APK_GLOB" || true
  else
    find "$PROJECT_ROOT" -type f -path '*/build/outputs/apk/*' -name '*.apk' | sort
  fi
)

((${#APKS[@]} > 0)) || fail "Build riuscita ma nessun APK trovato." 30

OUTPUT_NAME="${UAB_OUTPUT_NAME:-$(basename "$PROJECT_ROOT")}"

if ((${#APKS[@]} == 1)); then
  cp "${APKS[0]}" "$OUTPUT_DIR/apk/$OUTPUT_NAME.apk"
else
  i=1
  for apk in "${APKS[@]}"; do
    base="$(basename "$apk")"
    cp "$apk" "$OUTPUT_DIR/apk/${OUTPUT_NAME}-${i}-${base}"
    ((i++))
  done
fi

(
  cd "$OUTPUT_DIR/apk"
  sha256sum ./*.apk > SHA256SUMS.txt
  zip -9 -q "$OUTPUT_DIR/${OUTPUT_NAME}-APK.zip" ./*.apk SHA256SUMS.txt
)

unzip -tq "$OUTPUT_DIR/${OUTPUT_NAME}-APK.zip" >/dev/null
status "BUILD OK"
status "APK: $OUTPUT_DIR/apk"
status "ZIP: $OUTPUT_DIR/${OUTPUT_NAME}-APK.zip"
status "LOG: $LOG_FILE"
