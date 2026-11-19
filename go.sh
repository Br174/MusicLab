#!/usr/bin/env bash
# Minimal Android keyboard entrypoint for existing Codespace (no GitHub Actions).
set -Eeuo pipefail
exec bash "$(cd "$(dirname "$0")" && pwd)/scripts/codespaces-r70-autobuild.sh"
