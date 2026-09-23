#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$PWD}"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
PROTOC_VERSION="${UAB_PROTOC_VERSION:-34.0}"

status() { printf '[UAB][Project pre-build] %s\n' "$*"; }
fail() { printf '[UAB][Project pre-build][ERROR] %s\n' "$*" >&2; exit "${2:-21}"; }

[[ -f "$PROJECT_ROOT/app/generate_proto.sh" ]] || fail "app/generate_proto.sh non trovato." 21
[[ -f "$PROJECT_ROOT/metroproto/listentogether.proto" ]] || fail "metroproto/listentogether.proto non trovato. Verificare il submodule metroproto." 22

if ! command -v protoc >/dev/null 2>&1; then
  status "protoc non presente: installazione automatica..."
  if command -v brew >/dev/null 2>&1; then
    brew install protobuf
  elif command -v apt-get >/dev/null 2>&1; then
    if command -v sudo >/dev/null 2>&1; then
      sudo apt-get update
      sudo apt-get install -y protobuf-compiler
    else
      apt-get update
      apt-get install -y protobuf-compiler
    fi
  else
    fail "Nessun installer supportato per protoc su questo host. Configurare protoc ${PROTOC_VERSION} nel provider." 23
  fi
fi

status "protoc disponibile: $(protoc --version)"
status "Rigenero le classi Protobuf dal submodule metroproto..."
(
  cd "$PROJECT_ROOT/app"
  bash generate_proto.sh
)
status "Generazione Protobuf completata."
