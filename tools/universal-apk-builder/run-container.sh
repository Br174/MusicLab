#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="${UAB_IMAGE:-universal-apk-builder:android36}"
CACHE_DIR="${UAB_CACHE_DIR:-$HOME/.uab/gradle-cache}"

mkdir -p "$CACHE_DIR"

ENGINE=""
if command -v docker >/dev/null 2>&1; then
  ENGINE=docker
elif command -v podman >/dev/null 2>&1; then
  ENGINE=podman
else
  echo '[UAB][ERROR] Né Docker né Podman sono disponibili. Usa build-apk.sh su un host con Android SDK oppure installa un motore container.' >&2
  exit 40
fi

if ! "$ENGINE" image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "[UAB] Creo l'ambiente Android riutilizzabile: $IMAGE"
  "$ENGINE" build -t "$IMAGE" "$SCRIPT_DIR"
fi

echo "[UAB] Avvio build in ambiente isolato"
"$ENGINE" run --rm \
  -v "$PROJECT_ROOT:/workspace" \
  -v "$CACHE_DIR:/root/.gradle" \
  -w /workspace \
  "$IMAGE" \
  bash /workspace/tools/universal-apk-builder/build-apk.sh /workspace
