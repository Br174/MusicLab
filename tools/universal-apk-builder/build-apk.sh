#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
CONFIG_FILE="${UAB_CONFIG_FILE:-$PROJECT_ROOT/uab-project.env}"
OUTPUT_DIR="${UAB_OUTPUT_DIR:-$PROJECT_ROOT/dist/uab}"
LOG_DIR="$OUTPUT_DIR/logs"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="$LOG_DIR/build-$TIMESTAMP.log"
PATCHED_GRADLE_FILE=""
PATCHED_GRADLE_BACKUP=""

mkdir -p "$LOG_DIR"

if [[ -f "$CONFIG_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
fi

status() { printf '[UAB] %s\n' "$*"; }
progress() {
  local pct="$1"
  shift
  printf '[UAB_PROGRESS=%s] %s\n' "$pct" "$*"
}
fail() { printf '[UAB][ERROR] %s\n' "$*" >&2; exit "${2:-1}"; }

progress 5 "Motore UAB avviato"

cleanup() {
  if [[ -n "$PATCHED_GRADLE_FILE" && -n "$PATCHED_GRADLE_BACKUP" && -f "$PATCHED_GRADLE_BACKUP" ]]; then
    cp "$PATCHED_GRADLE_BACKUP" "$PATCHED_GRADLE_FILE"
    rm -f "$PATCHED_GRADLE_BACKUP"
  fi
}
trap cleanup EXIT

patch_application_id_generic() {
  local app_id="$1"
  local gradle_file="${UAB_APPLICATION_ID_GRADLE_FILE:-}"

  if [[ -n "$gradle_file" && "$gradle_file" != /* ]]; then
    gradle_file="$PROJECT_ROOT/$gradle_file"
  fi

  if [[ -z "$gradle_file" ]]; then
    while IFS= read -r candidate; do
      if grep -Eq 'applicationId[[:space:]]*(=|[[:space:]])' "$candidate"; then
        gradle_file="$candidate"
        break
      fi
    done < <(find "$PROJECT_ROOT" -maxdepth 3 \( -name build.gradle -o -name build.gradle.kts \) -type f | sort)
  fi

  [[ -n "$gradle_file" && -f "$gradle_file" ]] || fail "Nessun file Gradle con applicationId rilevato. Configurare UAB_APPLICATION_ID_ENV o UAB_APPLICATION_ID_GRADLE_FILE." 25
  command -v python3 >/dev/null 2>&1 || fail "python3 richiesto per il fallback generico applicationId." 25

  PATCHED_GRADLE_FILE="$gradle_file"
  PATCHED_GRADLE_BACKUP="$(mktemp)"
  cp "$gradle_file" "$PATCHED_GRADLE_BACKUP"

  python3 - "$gradle_file" "$app_id" <<'PY'
import re, sys
path, app_id = sys.argv[1], sys.argv[2]
text = open(path, encoding='utf-8').read()
patterns = [
    r'(applicationId\s*=\s*)["\'][^"\']+["\']',
    r'(applicationId\s+)["\'][^"\']+["\']',
]
for pat in patterns:
    new, count = re.subn(pat, lambda m: m.group(1) + '"' + app_id + '"', text, count=1)
    if count:
        open(path, 'w', encoding='utf-8').write(new)
        sys.exit(0)
sys.exit(2)
PY
  rc=$?
  if (( rc != 0 )); then
    cleanup
    PATCHED_GRADLE_FILE=""
    PATCHED_GRADLE_BACKUP=""
    fail "applicationId non sostituibile automaticamente; usare l'adapter del progetto." 25
  fi
  status "Application ID iniettato temporaneamente in ${gradle_file#$PROJECT_ROOT/}; il file verrà ripristinato a fine build."
}

setup_parallel_install() {
  local enabled="${UAB_PARALLEL_INSTALL:-off}"
  local enabled_norm
  enabled_norm="$(printf '%s' "$enabled" | tr '[:upper:]' '[:lower:]')"
  case "$enabled_norm" in
    1|true|yes|on) ;;
    *) return 0 ;;
  esac

  local base="${UAB_APPLICATION_ID_BASE:-}"
  local id_env="${UAB_APPLICATION_ID_ENV:-}"
  [[ -n "$base" ]] || fail "UAB_PARALLEL_INSTALL attivo ma UAB_APPLICATION_ID_BASE non configurato." 25

  local generated="${UAB_BUILD_ID:-b$(date -u +%Y%m%d%H%M%S)}"
  generated="$(printf '%s' "$generated" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_]/_/g')"
  [[ "$generated" =~ ^[a-z] ]] || generated="b$generated"

  UAB_EFFECTIVE_APPLICATION_ID="${base}.${generated}"
  export UAB_EFFECTIVE_APPLICATION_ID UAB_BUILD_ID="$generated"

  if [[ -n "$id_env" ]]; then
    printf -v "$id_env" '%s' "$UAB_EFFECTIVE_APPLICATION_ID"
    export "$id_env"
    status "Application ID passato tramite adapter: $id_env"
  else
    patch_application_id_generic "$UAB_EFFECTIVE_APPLICATION_ID"
  fi

  local name_env="${UAB_APP_NAME_ENV:-}"
  local name_base="${UAB_APP_NAME_BASE:-}"
  if [[ -n "$name_env" && -n "$name_base" ]]; then
    local short_id="${generated#b}"
    short_id="${short_id:8:6}"
    [[ -n "$short_id" ]] || short_id="$generated"
    UAB_EFFECTIVE_APP_NAME="${name_base} ${short_id}"
    export UAB_EFFECTIVE_APP_NAME
    printf -v "$name_env" '%s' "$UAB_EFFECTIVE_APP_NAME"
    export "$name_env"
  fi

  status "Installazione parallela: ON"
  status "Application ID LAB: $UAB_EFFECTIVE_APPLICATION_ID"
  [[ -n "${UAB_EFFECTIVE_APP_NAME:-}" ]] && status "Nome app LAB: $UAB_EFFECTIVE_APP_NAME"
}

run_hook() {
  local phase="$1"
  local configured_script="$2"
  local auto_script="$3"
  local script=""

  if [[ -n "$configured_script" ]]; then
    script="$configured_script"
  elif [[ -f "$auto_script" ]]; then
    script="$auto_script"
  fi

  [[ -n "$script" ]] || return 0
  [[ "$script" = /* ]] || script="$PROJECT_ROOT/$script"
  [[ -f "$script" ]] || fail "$phase hook configurato ma non trovato: $script" 24

  status "$phase hook: ${script#$PROJECT_ROOT/}"
  set +e
  bash "$script" "$PROJECT_ROOT" 2>&1 | tee -a "$LOG_FILE"
  local rc=${PIPESTATUS[0]}
  set -e
  if (( rc != 0 )); then
    printf '[UAB][FAILED] %s hook fallito (exit %s)\n[UAB][LOG] %s\n' "$phase" "$rc" "$LOG_FILE" >&2
    exit "$rc"
  fi
  status "$phase hook completato"
}

[[ -f "$PROJECT_ROOT/gradlew" ]] || fail "gradlew non trovato: il progetto non sembra un progetto Gradle Android standard." 10
chmod +x "$PROJECT_ROOT/gradlew"

command -v java >/dev/null 2>&1 || fail "Java/JDK non disponibile." 11
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
[[ -n "$JAVA_MAJOR" ]] || fail "Impossibile determinare la versione Java." 11
if (( JAVA_MAJOR < 17 )); then
  fail "JDK troppo vecchio: trovato Java $JAVA_MAJOR. Serve almeno Java 17." 12
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || fail "Android SDK non disponibile. Configurare ANDROID_SDK_ROOT/ANDROID_HOME oppure usare un host preparato da UAB." 13

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

progress 15 "Sorgenti e toolchain verificati"
setup_parallel_install

status "Progetto: $PROJECT_ROOT"
status "Java: $JAVA_MAJOR"
status "Android SDK: $SDK_ROOT"
status "Task: $TASK"
status "Output: $OUTPUT_DIR"

cd "$PROJECT_ROOT"
progress 30 "Configurazione progetto pronta"
run_hook "Pre-build" "${UAB_PRE_BUILD_SCRIPT:-}" "$PROJECT_ROOT/.uab/pre-build.sh"
progress 45 "Controlli/pre-build completati"

# Persistent acceleration is mandatory by UAB contract. Project adapters may add
# more arguments, but the engine guarantees --build-cache unless already present.
GRADLE_ARGS="${UAB_GRADLE_ARGS:-}"
case " $GRADLE_ARGS " in
  *" --build-cache "*) ;;
  *) GRADLE_ARGS="--build-cache $GRADLE_ARGS" ;;
esac
status "Gradle persistent build cache: ON"
progress 60 "Cache e preflight pronti; compilazione in avvio"

set +e
./gradlew "$TASK" --console=plain --warning-mode summary $GRADLE_ARGS 2>&1 | tee -a "$LOG_FILE"
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

progress 75 "Compilazione completata"
run_hook "Post-build" "${UAB_POST_BUILD_SCRIPT:-}" "$PROJECT_ROOT/.uab/post-build.sh"

rm -rf "$OUTPUT_DIR/apk"
mkdir -p "$OUTPUT_DIR/apk"

APKS=()
while IFS= read -r apk; do
  [[ -n "$apk" ]] && APKS+=("$apk")
done < <(
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

progress 85 "APK raccolto; verifica e ZIP in corso"
(
  cd "$OUTPUT_DIR/apk"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum ./*.apk > SHA256SUMS.txt
  else
    shasum -a 256 ./*.apk > SHA256SUMS.txt
  fi
  zip -9 -q "$OUTPUT_DIR/${OUTPUT_NAME}-APK.zip" ./*.apk SHA256SUMS.txt
)

unzip -tq "$OUTPUT_DIR/${OUTPUT_NAME}-APK.zip" >/dev/null
progress 90 "APK/ZIP verificati; pronti per pubblicazione stabile"
status "BUILD OK"
status "APK: $OUTPUT_DIR/apk"
status "ZIP: $OUTPUT_DIR/${OUTPUT_NAME}-APK.zip"
status "LOG: $LOG_FILE"
