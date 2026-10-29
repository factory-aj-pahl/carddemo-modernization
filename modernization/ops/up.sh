#!/usr/bin/env bash
# Starts the stack on 127.0.0.1: Airflow (api-server, scheduler, dag-processor; port $CARDDEMO_AIRFLOW_PORT),
# the online API (port $CARDDEMO_API_PORT), and the web app (vite preview of web/dist, port
# $CARDDEMO_WEB_PORT, proxying /api to the API). Pids and logs are in $CARDDEMO_RUN (default
# ~/.carddemo/run); Airflow's own are in $AIRFLOW_HOME/run. A service that is already running is left
# alone, so running this twice is harmless. Ends with check_stack.py.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/stack-env.sh"
require_secrets CARDDEMO_DB_PASSWORD RABBITMQ_PASSWORD
require_file "$CARDDEMO_API_JAR"
require_file "$CARDDEMO_BATCH_JAR"
require_file "$CARDDEMO_MODERNIZATION/web/dist/index.html"
mkdir -p "$CARDDEMO_RUN" "$CARDDEMO_OUTBOX" "$CARDDEMO_INBOX"

# Airflow first: its api-server writes the generated password the API needs for report runs.
# Its DAG tasks run the batch jar with this environment (CARDDEMO_DB, CARDDEMO_DB_PASSWORD).
export AIRFLOW__API__PORT="$CARDDEMO_AIRFLOW_PORT"
"$CARDDEMO_MODERNIZATION/airflow/start.sh"
wait_http "http://127.0.0.1:$CARDDEMO_AIRFLOW_PORT/api/v2/monitor/health" 180
for _ in $(seq 30); do [ -s "$AIRFLOW_PASSWORD_FILE" ] && break; sleep 1; done
require_file "$AIRFLOW_PASSWORD_FILE"
AIRFLOW_PASSWORD="$(python3 -c 'import json,os,sys; print(json.load(open(sys.argv[1]))[os.environ["AIRFLOW_USERNAME"]])' "$AIRFLOW_PASSWORD_FILE")"
export AIRFLOW_PASSWORD

if pid_alive "$CARDDEMO_RUN/api.pid"; then
    echo "api already running (pid $(cat "$CARDDEMO_RUN/api.pid"))"
else
    SERVER_PORT="$CARDDEMO_API_PORT" AIRFLOW_BASE_URL="http://127.0.0.1:$CARDDEMO_AIRFLOW_PORT" \
        setsid nohup java -Xmx512m -jar "$CARDDEMO_API_JAR" >"$CARDDEMO_RUN/api.log" 2>&1 </dev/null &
    echo $! >"$CARDDEMO_RUN/api.pid"
    echo "api started (pid $!, log $CARDDEMO_RUN/api.log)"
fi
if ! wait_http "http://127.0.0.1:$CARDDEMO_API_PORT/actuator/health" 180 '"UP"'; then
    tail -n 40 "$CARDDEMO_RUN/api.log" >&2
    exit 1
fi

if pid_alive "$CARDDEMO_RUN/web.pid"; then
    echo "web already running (pid $(cat "$CARDDEMO_RUN/web.pid"))"
else
    (cd "$CARDDEMO_MODERNIZATION/web" && CARDDEMO_API_URL="http://127.0.0.1:$CARDDEMO_API_PORT" \
        setsid nohup ./node_modules/.bin/vite preview >"$CARDDEMO_RUN/web.log" 2>&1 </dev/null &
        echo $! >"$CARDDEMO_RUN/web.pid")
    echo "web started (pid $(cat "$CARDDEMO_RUN/web.pid"), log $CARDDEMO_RUN/web.log)"
fi
wait_http "http://127.0.0.1:$CARDDEMO_WEB_PORT/" 60

python3 "$here/check_stack.py" --wait 120
echo "CardDemo: http://127.0.0.1:$CARDDEMO_WEB_PORT  (API http://127.0.0.1:$CARDDEMO_API_PORT, Airflow http://127.0.0.1:$CARDDEMO_AIRFLOW_PORT)"
