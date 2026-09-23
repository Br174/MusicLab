#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
REPO="${UAB_GITHUB_REPOSITORY:-}"
WORKFLOW="${UAB_GITHUB_WORKFLOW:-}"
BRANCH="${UAB_BRANCH:-}"
WAIT="${UAB_GITHUB_WAIT:-1}"
TIMEOUT="${UAB_GITHUB_TIMEOUT_SECONDS:-2700}"

if [[ -z "$REPO" ]]; then
  remote="$(git -C "$PROJECT_ROOT" remote get-url origin 2>/dev/null || true)"
  if [[ "$remote" =~ github.com[:/]([^/]+)/([^/.]+)(\.git)?$ ]]; then
    REPO="${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
  fi
fi
if [[ -z "$BRANCH" ]]; then
  BRANCH="$(git -C "$PROJECT_ROOT" branch --show-current 2>/dev/null || true)"
fi

[[ -n "$TOKEN" ]] || { echo "[UAB][GitHub] Token Actions non configurato."; exit 21; }
[[ -n "$REPO" ]] || { echo "[UAB][GitHub] Repository non determinabile; impostare UAB_GITHUB_REPOSITORY."; exit 21; }
[[ -n "$WORKFLOW" ]] || { echo "[UAB][GitHub] UAB_GITHUB_WORKFLOW non configurato per questo progetto."; exit 21; }
[[ -n "$BRANCH" ]] || { echo "[UAB][GitHub] Branch non determinabile; impostare UAB_BRANCH."; exit 21; }
command -v curl >/dev/null 2>&1 || { echo "[UAB][GitHub] curl non disponibile."; exit 22; }
command -v python3 >/dev/null 2>&1 || { echo "[UAB][GitHub] python3 non disponibile."; exit 22; }

owner="${REPO%%/*}"
limit="${UAB_GITHUB_INCLUDED_MINUTES:-}"
if [[ -n "$limit" ]]; then
  usage_file="$(mktemp)"
  usage_code=$(curl -sS -o "$usage_file" -w '%{http_code}' \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/users/$owner/settings/billing/usage?product=Actions" || true)
  if [[ "$usage_code" == "200" ]]; then
    used=$(python3 - "$usage_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    items=data.get('usageItems',[])
    print(int(sum(float(i.get('quantity',0) or 0) for i in items if str(i.get('unitType','')).lower()=='minutes')))
except Exception:
    print(0)
PY
)
    echo "[UAB][GitHub] Uso Actions rilevato: ${used}/${limit} minuti."
    rm -f "$usage_file"
    if (( used >= limit )); then
      echo "[UAB][GitHub] Quota gratuita configurata esaurita; fallback."
      exit 20
    fi
  else
    rm -f "$usage_file"
    echo "[UAB][GitHub] Lettura quota non disponibile (HTTP $usage_code); continuo solo se l'account blocca l'overage tramite budget."
  fi
fi

response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT
payload=$(printf '{"ref":"%s"}' "$BRANCH")
dispatch_epoch=$(date -u +%s)
http_code=$(curl -sS -o "$response_file" -w '%{http_code}' \
  -X POST \
  -H 'Accept: application/vnd.github+json' \
  -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/dispatches" \
  -d "$payload" || true)

case "$http_code" in
  200|201|202|204) ;;
  402|429) echo "[UAB][GitHub] Quota/limite raggiunto; fallback."; exit 20 ;;
  401|403) echo "[UAB][GitHub] Actions non autorizzato o uso bloccato; fallback."; exit 20 ;;
  404) echo "[UAB][GitHub] Workflow/repository non disponibile."; exit 21 ;;
  500|502|503|504) echo "[UAB][GitHub] Servizio temporaneamente non disponibile; fallback."; exit 20 ;;
  *) echo "[UAB][GitHub] Dispatch rifiutato (HTTP $http_code)."; cat "$response_file" 2>/dev/null || true; exit 22 ;;
esac

echo "[UAB][GitHub] Workflow dispatch accettato."
[[ "$WAIT" == "1" ]] || exit 0

run_id=""
discovery_start=$(date +%s)
while [[ -z "$run_id" ]]; do
  now=$(date +%s)
  if (( now - discovery_start > 90 )); then
    echo "[UAB][GitHub] Run non comparsa entro 90s: considero runner/servizio non disponibile."
    exit 20
  fi
  runs_file="$(mktemp)"
  runs_code=$(curl -sS -o "$runs_file" -w '%{http_code}' \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&branch=$BRANCH&per_page=10" || true)
  if [[ "$runs_code" == "200" ]]; then
    run_id=$(python3 - "$runs_file" "$dispatch_epoch" <<'PY'
import json,sys,datetime
try:
    data=json.load(open(sys.argv[1]))
    threshold=int(sys.argv[2]) - 15
    for r in data.get('workflow_runs',[]):
        created=r.get('created_at')
        if not created: continue
        epoch=int(datetime.datetime.fromisoformat(created.replace('Z','+00:00')).timestamp())
        if epoch >= threshold:
            print(r.get('id',''))
            break
except Exception:
    pass
PY
)
  fi
  rm -f "$runs_file"
  [[ -n "$run_id" ]] || sleep 5
done

echo "[UAB][GitHub] Run rilevata: $run_id"
start=$(date +%s)
while :; do
  now=$(date +%s)
  if (( now - start > TIMEOUT )); then
    echo "[UAB][GitHub] Timeout attesa workflow."
    exit 20
  fi
  run_file="$(mktemp)"
  code=$(curl -sS -o "$run_file" -w '%{http_code}' \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/actions/runs/$run_id" || true)
  if [[ "$code" != "200" ]]; then
    rm -f "$run_file"
    sleep 10
    continue
  fi
  read -r status conclusion < <(python3 - "$run_file" <<'PY'
import json,sys
j=json.load(open(sys.argv[1])); print(j.get('status',''), j.get('conclusion') or '')
PY
)
  rm -f "$run_file"
  [[ "$status" == "completed" ]] || { sleep 10; continue; }

  if [[ "$conclusion" == "success" ]]; then
    echo "[UAB][GitHub] Build completata con successo."
    exit 0
  fi

  jobs_file="$(mktemp)"
  jobs_code=$(curl -sS -o "$jobs_file" -w '%{http_code}' \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/actions/runs/$run_id/jobs" || true)
  jobs_count=0
  steps_count=0
  if [[ "$jobs_code" == "200" ]]; then
    read -r jobs_count steps_count < <(python3 - "$jobs_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    jobs=data.get('jobs',[])
    print(len(jobs), sum(len(j.get('steps') or []) for j in jobs))
except Exception:
    print(0,0)
PY
)
  fi
  rm -f "$jobs_file"

  if (( jobs_count == 0 || steps_count == 0 )) || [[ "$conclusion" =~ ^(startup_failure|action_required|stale|cancelled)$ ]]; then
    echo "[UAB][GitHub] Nessuno step eseguito: runner/quota/servizio non disponibile; fallback."
    exit 20
  fi

  echo "[UAB][GitHub] Workflow eseguito ma build fallita ($conclusion): considero errore di progetto e non spreco altri provider."
  exit 30
done
