#!/usr/bin/env bash
# Starts the CardDemo Airflow services on 127.0.0.1: api-server (UI and REST API, port
# $AIRFLOW__API__PORT, default 8080), scheduler, and dag-processor. Each runs in its own process
# group; pids and logs are in $AIRFLOW_HOME/run. Stop them with stop.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$here/env.sh"
run="$AIRFLOW_HOME/run"
mkdir -p "$run"
airflow db migrate >"$run/db-migrate.log" 2>&1
# The report DAG is triggered on request (web report screen), so it must not stay paused.
airflow dags reserialize >"$run/reserialize.log" 2>&1
airflow dags unpause carddemo_transaction_report >>"$run/reserialize.log" 2>&1 || echo "could not unpause carddemo_transaction_report (see $run/reserialize.log)"
for service in api-server scheduler dag-processor; do
    pidfile="$run/$service.pid"
    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "$service already running (pid $(cat "$pidfile"))"
        continue
    fi
    if [ "$service" = api-server ]; then
        args=(api-server --host "$AIRFLOW__API__HOST" --port "$AIRFLOW__API__PORT")
    else
        args=("$service")
    fi
    setsid nohup airflow "${args[@]}" >"$run/$service.log" 2>&1 </dev/null &
    echo $! >"$pidfile"
    echo "$service started (pid $!, log $run/$service.log)"
done
echo "Airflow UI and API: http://$AIRFLOW__API__HOST:$AIRFLOW__API__PORT"
