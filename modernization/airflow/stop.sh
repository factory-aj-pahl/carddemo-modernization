#!/usr/bin/env bash
# Stops the services started by start.sh (the whole process group of each), waiting up to 30 seconds
# before killing what is left.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$here/env.sh"
run="$AIRFLOW_HOME/run"
for service in api-server scheduler dag-processor; do
    pidfile="$run/$service.pid"
    [ -f "$pidfile" ] || continue
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
        for _ in $(seq 30); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -KILL -- "-$pid" 2>/dev/null || true
        echo "$service stopped (pid $pid)"
    fi
    rm -f "$pidfile"
done
