#!/bin/sh
# launchd entry point for the Bridge: launch-bridge.sh <python> <args...>
#
# launchd restarts the Bridge after a failed exit (KeepAlive SuccessfulExit=false, ThrottleInterval
# 15s). This caps that at MAX_RESTARTS in a row when the Bridge cannot get Hermes up, e.g. when the
# external disk holding its Python is not mounted. The Bridge resets the count once Hermes is ready.
# Giving up exits 0 (launchd stops retrying) and clears the count, so the next login, kickstart or
# install-mac.sh run gets a fresh set of tries.
#
# Lives in the data dir on the internal disk and uses only /bin/sh, so it runs without that disk.

MAX_RESTARTS=5
COUNT_FILE="${HERMES_BRIDGE_DATA_DIR:?}/start-attempts"

python="$1"
shift

failed=$(cat "$COUNT_FILE" 2>/dev/null)
case "$failed" in
  '' | *[!0-9]*) failed=0 ;;
esac

if [ "$failed" -gt "$MAX_RESTARTS" ]; then
  echo "$(date '+%Y-%m-%d %H:%M:%S') Bridge did not get Hermes up in $failed launches; not retrying." \
    "Run scripts/install-mac.sh or: launchctl kickstart gui/$(id -u)/ai.hermes.mobile-bridge" >&2
  rm -f "$COUNT_FILE"
  exit 0
fi

echo $((failed + 1)) > "$COUNT_FILE"
exec "$python" "$@"
