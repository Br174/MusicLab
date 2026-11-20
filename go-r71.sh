#!/usr/bin/env bash
# Optional Spotify-login R71 A/B trial; never replaces the stable R70 entrypoint.
# Same Codespace, signed Turbo update family, zero GitHub Actions.
set -Eeuo pipefail
exec bash "$(cd "$(dirname "$0")" && pwd)/scripts/codespaces-r71-autobuild.sh"
