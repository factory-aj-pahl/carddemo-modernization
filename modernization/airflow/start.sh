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
# The daily cycle is scheduled (22:00 UTC, Monday to Friday) and posts against $CARDDEMO_DB, so it stays paused unless
# the operator asks for the schedule; manual runs need it unpaused too (verify_report.py unpauses it for its run).
if [ "${CARDDEMO_CYCLE_SCHEDULE:-off}" = on ]; then
    airflow dags unpause carddemo_daily_cycle >>"$run/reserialize.log" 2>&1 || echo "could not unpause carddemo_daily_cycle (see $run/reserialize.log)"
    echo "carddemo_daily_cycle schedule on (CARDDEMO_CYCLE_SCHEDULE=on)"
else
    airflow dags pause carddemo_daily_cycle >>"$run/reserialize.log" 2>&1 || echo "could not pause carddemo_daily_cycle (see $run/reserialize.log)"
    echo "carddemo_daily_cycle paused; schedule it with: airflow dags unpause carddemo_daily_cycle (or CARDDEMO_CYCLE_SCHEDULE=on)"
fi
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
