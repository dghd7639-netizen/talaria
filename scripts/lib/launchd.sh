#!/usr/bin/env bash

bootstrap_launch_agent() {
  local domain="$1"
  local plist_path="$2"
  local launchctl_bin="${LAUNCHCTL_BIN:-launchctl}"
  local retry_delay="${LAUNCHD_RETRY_DELAY:-0.5}"
  local attempt

  for attempt in {1..5}; do
    if "${launchctl_bin}" bootstrap "${domain}" "${plist_path}"; then
      return 0
    fi
    if (( attempt < 5 )); then
      sleep "${retry_delay}"
    fi
  done
  return 1
}
