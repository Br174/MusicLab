#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
ROUTER_CONFIG="${UAB_ROUTER_CONFIG:-$PROJECT_ROOT/uab-router.env}"
CORE_PROVIDER_DIR="$SCRIPT_DIR/providers"
PROJECT_PROVIDER_DIR="$PROJECT_ROOT/.uab/providers"
ROUTER_LOG_DIR="$PROJECT_ROOT/dist/uab/router"
mkdir -p "$ROUTER_LOG_DIR"
ROUTER_LOG="$ROUTER_LOG_DIR/router-$(date -u +%Y%m%dT%H%M%SZ).log"

if [[ -f "$ROUTER_CONFIG" ]]; then
  # shellcheck disable=SC1090
  source "$ROUTER_CONFIG"
fi

UAB_PROVIDER_ORDER="${UAB_PROVIDER_ORDER:-local,github-actions,codemagic}"
UAB_PAID_FALLBACK="${UAB_PAID_FALLBACK:-never}"
UAB_REUSE_READY_ARTIFACTS="${UAB_REUSE_READY_ARTIFACTS:-on}"
UAB_PROJECT_NAME="${UAB_PROJECT_NAME:-$(basename "$PROJECT_ROOT")}"
UAB_MANIFEST_REPOSITORY="${UAB_MANIFEST_REPOSITORY:-}"
UAB_MANIFEST_BRANCH="${UAB_MANIFEST_BRANCH:-main}"
UAB_MANIFEST_PATH="${UAB_MANIFEST_PATH:-uab-artifacts/{project}/latest.json}"
UAB_ARTIFACT_REUSE_IGNORE_REGEX="${UAB_ARTIFACT_REUSE_IGNORE_REGEX:-}"

log(){ printf '[UAB-ROUTER] %s\n' "$*" | tee -a "$ROUTER_LOG"; }

check_ready_artifact() {
  local reuse token manifest_path current_commit current_branch tmp http_code result first_line manifest_commit changed_files non_ignored
  reuse="$(printf '%s' "$UAB_REUSE_READY_ARTIFACTS" | tr '[:upper:]' '[:lower:]')"
  case "$reuse" in
    1|true|yes|on) ;;
    *) log "Artifact-first disattivato da configurazione."; return 1 ;;
  esac

  if [[ -z "$UAB_MANIFEST_REPOSITORY" ]]; then
    log "Artifact-first: registro centrale non configurato; continuo con il router."
    return 1
  fi
  command -v git >/dev/null 2>&1 || { log "Artifact-first: git non disponibile; continuo."; return 1; }
  command -v curl >/dev/null 2>&1 || { log "Artifact-first: curl non disponibile; continuo."; return 1; }
  command -v python3 >/dev/null 2>&1 || { log "Artifact-first: python3 non disponibile; continuo."; return 1; }

  current_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || true)"
  current_branch="$(git -C "$PROJECT_ROOT" branch --show-current 2>/dev/null || true)"
  [[ -n "$current_commit" ]] || { log "Artifact-first: commit corrente non determinabile; continuo."; return 1; }

  token="${UAB_GITHUB_MANIFEST_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
  if [[ -z "$token" ]]; then
    log "Artifact-first: token API non disponibile nell'host. La chat deve leggere latest.json tramite il collegamento GitHub prima di avviare il router."
    return 1
  fi

  manifest_path="${UAB_MANIFEST_PATH//\{project\}/$UAB_PROJECT_NAME}"
  tmp="$(mktemp)"
  http_code=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer $token" \
    "https://api.github.com/repos/$UAB_MANIFEST_REPOSITORY/contents/$manifest_path?ref=$UAB_MANIFEST_BRANCH" || true)

  if [[ "$http_code" != "200" ]]; then
    rm -f "$tmp"
    log "Artifact-first: manifest non leggibile (HTTP $http_code); continuo con i provider."
    return 1
  fi

  result=$(python3 - "$tmp" "${UAB_GITHUB_REPOSITORY:-}" <<'PY'
import base64, json, sys
path, source_repo = sys.argv[1:3]
try:
    outer = json.load(open(path, encoding='utf-8'))
    raw = base64.b64decode((outer.get('content') or '').replace('\n','')).decode('utf-8')
    data = json.loads(raw)
except Exception:
    sys.exit(2)
if data.get('status') != 'ready' or not data.get('commit'):
    sys.exit(3)
if source_repo and data.get('sourceRepository') and data.get('sourceRepository') != source_repo:
    sys.exit(4)
arts = data.get('artifacts') or []
apks = [a for a in arts if a.get('kind') == 'apk' and a.get('url')]
zips = [a for a in arts if a.get('kind') == 'zip' and a.get('url')]
if not apks or not zips:
    sys.exit(5)
print(f"READY\t{data.get('commit')}")
for a in apks + zips:
    print(f"{a.get('kind','').upper()}\t{a.get('name','artifact')}\t{a.get('url','')}")
PY
  ) || {
    rm -f "$tmp"
    log "Artifact-first: manifest non contiene un APK/ZIP pronto e valido; serve una build."
    return 1
  }
  rm -f "$tmp"

  first_line="$(printf '%s\n' "$result" | head -n1)"
  manifest_commit="${first_line#READY$'\t'}"
  [[ "$first_line" == READY$'\t'* && -n "$manifest_commit" ]] || return 1

  if [[ "$manifest_commit" != "$current_commit" ]]; then
    if [[ -z "$UAB_ARTIFACT_REUSE_IGNORE_REGEX" ]]; then
      log "Artifact-first: il commit dell'app non coincide con il manifest; serve una build."
      return 1
    fi
    if ! git -C "$PROJECT_ROOT" cat-file -e "$manifest_commit^{commit}" 2>/dev/null; then
      log "Artifact-first: commit del manifest non presente nel clone locale; impossibile verificare il riuso in sicurezza."
      return 1
    fi
    changed_files="$(git -C "$PROJECT_ROOT" diff --name-only "$manifest_commit".."$current_commit" 2>/dev/null || true)"
    if [[ -n "$changed_files" ]]; then
      non_ignored="$(printf '%s\n' "$changed_files" | grep -Ev "$UAB_ARTIFACT_REUSE_IGNORE_REGEX" || true)"
      if [[ -n "$non_ignored" ]]; then
        log "Artifact-first: sono cambiati file che possono modificare l'app; serve una nuova build."
        return 1
      fi
      log "Artifact-first: dal manifest sono cambiati solo file infrastrutturali ignorabili; APK/ZIP restano validi."
    fi
  fi

  log "Artifact-first: build già pronta e compatibile. Nessuna ricompilazione."
  printf '%s\n' "$result" | tail -n +2 | while IFS=$'\t' read -r kind name url; do
    [[ -n "$url" ]] || continue
    printf '[UAB-ARTIFACT] %s: %s -> %s\n' "$kind" "$name" "$url" | tee -a "$ROUTER_LOG"
  done
  return 0
}

if [[ "$UAB_PAID_FALLBACK" != "never" ]]; then
  log "ERRORE: UAB_PAID_FALLBACK deve rimanere 'never'."
  exit 60
fi

log "Progetto: $PROJECT_ROOT"
log "Ordine provider: $UAB_PROVIDER_ORDER"
log "Protezione costi: nessun fallback a pagamento"

if check_ready_artifact; then
  log "Richiesta soddisfatta dalla build già pronta. Router terminato con successo."
  exit 0
fi

IFS=',' read -r -a PROVIDERS <<< "$UAB_PROVIDER_ORDER"
for provider in "${PROVIDERS[@]}"; do
  provider="$(printf '%s' "$provider" | xargs)"
  [[ -n "$provider" ]] || continue

  adapter=""
  if [[ -f "$PROJECT_PROVIDER_DIR/$provider.sh" ]]; then
    adapter="$PROJECT_PROVIDER_DIR/$provider.sh"
  elif [[ -f "$CORE_PROVIDER_DIR/$provider.sh" ]]; then
    adapter="$CORE_PROVIDER_DIR/$provider.sh"
  fi

  if [[ -z "$adapter" ]]; then
    log "$provider: adapter assente, passo al successivo."
    continue
  fi

  log "$provider: preflight/tentativo in corso..."
  set +e
  bash "$adapter" "$PROJECT_ROOT" 2>&1 | tee -a "$ROUTER_LOG"
  rc=${PIPESTATUS[0]}
  set -e

  case "$rc" in
    0)
      log "$provider: build accettata/completata. Router terminato con successo."
      exit 0
      ;;
    20|21|22)
      log "$provider: quota/servizio/configurazione non disponibile. Fallback immediato al successivo."
      ;;
    30)
      log "$provider: build partita ma fallita per problema del progetto/codice. Non consumo altri provider."
      exit 30
      ;;
    *)
      log "$provider: errore infrastrutturale non classificato (rc=$rc). Fallback al successivo."
      ;;
  esac
done

log "Nessun provider gratuito disponibile. Nessun servizio a pagamento è stato attivato."
exit 50
