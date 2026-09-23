#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="${1:-$PWD}"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if ! command -v java >/dev/null 2>&1 || [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  echo "[UAB][Local] Ambiente Android locale non disponibile; fallback."
  exit 20
fi

set +e
bash "$PROJECT_ROOT/tools/universal-apk-builder/build-apk.sh" "$PROJECT_ROOT"
rc=$?
set -e

if (( rc == 0 )); then
  exit 0
fi

case "$rc" in
  10|11|12|13|14) exit 20 ;;
  *) exit 30 ;;
esac
