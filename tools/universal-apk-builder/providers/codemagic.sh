#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="${1:-$PWD}"

: "${UAB_BRANCH:=universal-apk-builder}"
: "${UAB_CODEMAGIC_WORKFLOW:=uab-musiclab-lab}"

if [[ -z "${CM_API_TOKEN:-}" || -z "${CODEMAGIC_APP_ID:-}" ]]; then
  echo "[UAB][Codemagic] Credenziali/API non configurate."
  exit 21
fi

command -v curl >/dev/null 2>&1 || { echo "[UAB][Codemagic] curl non disponibile."; exit 22; }

payload=$(printf '{"appId":"%s","workflowId":"%s","branch":"%s"}' "$CODEMAGIC_APP_ID" "$UAB_CODEMAGIC_WORKFLOW" "$UAB_BRANCH")
response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

http_code=$(curl -sS -o "$response_file" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "x-auth-token: $CM_API_TOKEN" \
  --data "$payload" \
  -X POST https://api.codemagic.io/builds || true)

case "$http_code" in
  200|201|202)
    build_id="$(python3 - "$response_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    print(data.get('buildId',''))
except Exception:
    print('')
PY
)"
    echo "[UAB][Codemagic] Build avviata${build_id:+: $build_id}."
    exit 0
    ;;
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
