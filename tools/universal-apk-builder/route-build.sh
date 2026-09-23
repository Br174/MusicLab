#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
ROUTER_CONFIG="${UAB_ROUTER_CONFIG:-$PROJECT_ROOT/uab-router.env}"
PROVIDER_DIR="$PROJECT_ROOT/tools/universal-apk-builder/providers"
ROUTER_LOG_DIR="$PROJECT_ROOT/dist/uab/router"
mkdir -p "$ROUTER_LOG_DIR"
ROUTER_LOG="$ROUTER_LOG_DIR/router-$(date -u +%Y%m%dT%H%M%SZ).log"

if [[ -f "$ROUTER_CONFIG" ]]; then
  # shellcheck disable=SC1090
  source "$ROUTER_CONFIG"
fi

UAB_PROVIDER_ORDER="${UAB_PROVIDER_ORDER:-codemagic,github-actions,local}"
UAB_PAID_FALLBACK="${UAB_PAID_FALLBACK:-never}"

log(){ printf '[UAB-ROUTER] %s\n' "$*" | tee -a "$ROUTER_LOG"; }

if [[ "$UAB_PAID_FALLBACK" != "never" ]]; then
  log "ERRORE: UAB_PAID_FALLBACK deve rimanere 'never'."
  exit 60
fi

IFS=',' read -r -a PROVIDERS <<< "$UAB_PROVIDER_ORDER"
log "Ordine provider: $UAB_PROVIDER_ORDER"
log "Protezione costi: nessun fallback a pagamento"

for provider in "${PROVIDERS[@]}"; do
  provider="$(printf '%s' "$provider" | xargs)"
  [[ -n "$provider" ]] || continue
  adapter="$PROVIDER_DIR/$provider.sh"

  if [[ ! -f "$adapter" ]]; then
    log "$provider: adapter assente, passo al successivo."
    continue
  fi

  log "$provider: tentativo in corso..."
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
      log "$provider: quota/servizio/configurazione non disponibile. Provo il successivo."
      ;;
    30)
      log "$provider: build partita ma fallita per problema del progetto/codice. Non consumo altri provider."
      exit 30
      ;;
    *)
      log "$provider: errore infrastrutturale non classificato (rc=$rc). Provo il successivo."
      ;;
  esac
done

log "Nessun provider gratuito disponibile. Nessun servizio a pagamento è stato attivato."
exit 50
