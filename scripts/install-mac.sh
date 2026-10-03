#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/launchd.sh"

LABEL="ai.hermes.mobile-bridge"
BRIDGE_PORT="${HERMES_BRIDGE_PORT:-8788}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${HERMES_BRIDGE_DATA_DIR:-${HOME}/Library/Application Support/hermes-mobile-bridge}"
VENV_DIR="${DATA_DIR}/venv"
PLIST_PATH="${HOME}/Library/LaunchAgents/${LABEL}.plist"
PYTHON_BIN="${HERMES_PYTHON:-${HOME}/.local/bin/python3.11}"

fail() {
  printf 'Error: %s\n' "$1" >&2
  exit 1
}

if command -v tailscale >/dev/null 2>&1; then
  TAILSCALE_BIN="$(command -v tailscale)"
elif [[ -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ]]; then
  TAILSCALE_BIN="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
else
  fail "Tailscale CLI/app not found. Install Tailscale and sign in first."
fi

[[ -x "${PYTHON_BIN}" ]] || fail "Python 3.11 not found at ${PYTHON_BIN}"
[[ "${BRIDGE_PORT}" =~ ^[0-9]+$ ]] || fail "HERMES_BRIDGE_PORT must be numeric"
(( BRIDGE_PORT >= 1 && BRIDGE_PORT <= 65535 )) || fail "HERMES_BRIDGE_PORT must be 1..65535"

mkdir -p "${DATA_DIR}" "$(dirname "${PLIST_PATH}")"
chmod 700 "${DATA_DIR}"
"${PYTHON_BIN}" -m venv "${VENV_DIR}"
"${VENV_DIR}/bin/python" -m pip install --upgrade pip
"${VENV_DIR}/bin/python" -m pip install "${PROJECT_DIR}"

# launchd runs the Bridge through this launcher (internal disk, /bin/sh); a fresh install gets a
# full set of restarts.
install -m 700 "${PROJECT_DIR}/packaging/launch-bridge.sh" "${DATA_DIR}/launch-bridge.sh"
rm -f "${DATA_DIR}/start-attempts"

APK_PATH="${PROJECT_DIR}/android/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "${APK_PATH}" ]]; then
  APK_PATH="${PROJECT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk"
fi
if [[ -f "${APK_PATH}" ]]; then
  install -m 600 "${APK_PATH}" "${DATA_DIR}/hermes-mobile.apk"
  printf 'Published APK: %s\n' "${APK_PATH}"
fi

HERMES_BIN="$("${VENV_DIR}/bin/python" -c 'from hermes_mobile.settings import Settings; print(Settings().hermes_bin)')"
[[ -x "${HERMES_BIN}" ]] || fail "Hermes not found at ${HERMES_BIN}; set HERMES_BRIDGE_HERMES_BIN"
"${VENV_DIR}/bin/hermes-mobile-bridge" check-contract \
  || printf 'Warning: installed Hermes may be incompatible with this Bridge (see above).\n' >&2

"${VENV_DIR}/bin/python" - "${PROJECT_DIR}" "${DATA_DIR}" "${BRIDGE_PORT}" "${PLIST_PATH}" "${HERMES_BIN}" <<'PY'
from pathlib import Path
import sys

from hermes_mobile.cli import render_launchd_plist

project_dir, data_dir, port, output, hermes_bin = sys.argv[1:]
rendered = render_launchd_plist(
    venv_python=Path(data_dir) / "venv" / "bin" / "python",
    project_dir=Path(project_dir),
    data_dir=Path(data_dir),
    port=int(port),
    hermes_bin=Path(hermes_bin),
)
Path(output).write_text(rendered, encoding="utf-8")
PY

launchctl bootout "gui/${UID}/${LABEL}" >/dev/null 2>&1 || true
bootstrap_launch_agent "gui/${UID}" "${PLIST_PATH}"
launchctl kickstart -k "gui/${UID}/${LABEL}"

for _ in {1..120}; do
  if curl --fail --silent --max-time 1 "http://127.0.0.1:${BRIDGE_PORT}/v1/health" >/dev/null; then
    break
  fi
  sleep 0.5
done
curl --fail --silent --max-time 2 "http://127.0.0.1:${BRIDGE_PORT}/v1/health" >/dev/null \
  || fail "Bridge did not become healthy on 127.0.0.1:${BRIDGE_PORT}"

"${TAILSCALE_BIN}" serve --bg "localhost:${BRIDGE_PORT}"
TAILNET_HOST="$(${TAILSCALE_BIN} status --json | "${VENV_DIR}/bin/python" -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"

printf 'Bridge installed: https://%s\n' "${TAILNET_HOST}"
"${VENV_DIR}/bin/hermes-mobile-bridge" pair --base-url "https://${TAILNET_HOST}" \
  --port "${BRIDGE_PORT}" --output "${DATA_DIR}/talaria-pairing.png" \
  || printf '警告：生成配对二维码失败，Bridge 已安装；请稍后运行 hermes-mobile-bridge pair 重试。\n' >&2
