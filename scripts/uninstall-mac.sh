#!/usr/bin/env bash
set -euo pipefail

LABEL="ai.hermes.mobile-bridge"
PLIST_PATH="${HOME}/Library/LaunchAgents/${LABEL}.plist"

if command -v tailscale >/dev/null 2>&1; then
  tailscale serve reset || true
elif [[ -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ]]; then
  /Applications/Tailscale.app/Contents/MacOS/Tailscale serve reset || true
fi

launchctl bootout "gui/${UID}/${LABEL}" >/dev/null 2>&1 || true
rm -f "${PLIST_PATH}"
printf 'Bridge service removed. Data remains under %s\n' \
  "${HERMES_BRIDGE_DATA_DIR:-${HOME}/Library/Application Support/hermes-mobile-bridge}"
