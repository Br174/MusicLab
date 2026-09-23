#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
WORKFLOW="${UAB_CODEMAGIC_WORKFLOW:-}"
BRANCH="${UAB_BRANCH:-}"
WAIT="${UAB_CODEMAGIC_WAIT:-1}"
TIMEOUT="${UAB_CODEMAGIC_TIMEOUT_SECONDS:-2700}"

if [[ -z "$BRANCH" ]]; then
  BRANCH="$(git -C "$PROJECT_ROOT" branch --show-current 2>/dev/null || true)"
fi

if [[ -z "${CM_API_TOKEN:-}" || -z "${CODEMAGIC_APP_ID:-}" ]]; then
  echo "[UAB][Codemagic] Credenziali/API non configurate."
  exit 21
fi
[[ -n "$WORKFLOW" ]] || { echo "[UAB][Codemagic] UAB_CODEMAGIC_WORKFLOW non configurato per questo progetto."; exit 21; }
[[ -n "$BRANCH" ]] || { echo "[UAB][Codemagic] Branch non determinabile; impostare UAB_BRANCH."; exit 21; }
command -v curl >/dev/null 2>&1 || { echo "[UAB][Codemagic] curl non disponibile."; exit 22; }
command -v python3 >/dev/null 2>&1 || { echo "[UAB][Codemagic] python3 non disponibile."; exit 22; }

payload=$(printf '{"appId":"%s","workflowId":"%s","branch":"%s"}' "$CODEMAGIC_APP_ID" "$WORKFLOW" "$BRANCH")
response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

http_code=$(curl -sS -o "$response_file" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "x-auth-token: $CM_API_TOKEN" \
  --data "$payload" \
  -X POST https://api.codemagic.io/builds || true)

case "$http_code" in
  200|201|202) ;;
  401|403)
    echo "[UAB][Codemagic] Autorizzazione non disponibile; fallback consentito."
    exit 21
    ;;
  402|409|429)
    echo "[UAB][Codemagic] Quota/limite/servizio occupato; fallback consentito."
    exit 20
    ;;
  500|502|503|504)
    echo "[UAB][Codemagic] Servizio temporaneamente non disponibile; fallback consentito."
    exit 20
    ;;
  *)
    echo "[UAB][Codemagic] Avvio rifiutato (HTTP $http_code)."
    cat "$response_file" 2>/dev/null || true
    exit 22
    ;;
esac

build_id="$(python3 - "$response_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    print(data.get('buildId',''))
except Exception:
    print('')
PY
)"
[[ -n "$build_id" ]] || { echo "[UAB][Codemagic] Build accettata ma buildId non restituito."; exit 22; }
echo "[UAB][Codemagic] Build avviata: $build_id"
[[ "$WAIT" == "1" ]] || exit 0

start=$(date +%s)
while :; do
  now=$(date +%s)
  if (( now - start > TIMEOUT )); then
    echo "[UAB][Codemagic] Timeout attesa build; provo provider successivo."
    exit 20
  fi

  status_file="$(mktemp)"
  code=$(curl -sS -o "$status_file" -w '%{http_code}' \
    -H "x-auth-token: $CM_API_TOKEN" \
    "https://codemagic.io/api/v3/builds/$build_id" || true)

  if [[ "$code" != "200" ]]; then
    rm -f "$status_file"
    sleep 15
    continue
  fi

  status="$(python3 - "$status_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    print((data.get('data') or {}).get('status',''))
except Exception:
    print('')
PY
)"
  rm -f "$status_file"

  case "$status" in
    finished)
      echo "[UAB][Codemagic] Build completata con successo."
      exit 0
      ;;
    failed)
      echo "[UAB][Codemagic] Build eseguita ma fallita: considero errore del progetto e non spreco altri provider."
      exit 30
      ;;
    canceled|timeout|skipped)
      echo "[UAB][Codemagic] Build terminata con stato $status; fallback consentito."
      exit 20
      ;;
    initializing|queued|preparing|fetching|testing|building|publishing|finishing|"")
      sleep 15
      ;;
    *)
      echo "[UAB][Codemagic] Stato non riconosciuto: $status; attendo."
      sleep 15
      ;;
  esac
done
