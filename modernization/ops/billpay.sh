#!/usr/bin/env bash
# Starts the standalone bill-payment service alone (port $CARDDEMO_BILLPAY_PORT, default 8091) against
# $CARDDEMO_DB; stop it with down.sh. It signs users on itself (POST /api/screens/COSGN00C against the
# shared user table) and keeps its own server-side sessions.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/stack-env.sh"
require_secrets CARDDEMO_DB_PASSWORD
require_file "$CARDDEMO_BILLPAY_JAR"
mkdir -p "$CARDDEMO_RUN"
if pid_alive "$CARDDEMO_RUN/billpay.pid"; then
    echo "billpay already running (pid $(cat "$CARDDEMO_RUN/billpay.pid"))"
else
    SERVER_PORT="$CARDDEMO_BILLPAY_PORT" setsid nohup java -Xmx384m -jar "$CARDDEMO_BILLPAY_JAR" \
        >"$CARDDEMO_RUN/billpay.log" 2>&1 </dev/null &
    echo $! >"$CARDDEMO_RUN/billpay.pid"
    echo "billpay started (pid $!, log $CARDDEMO_RUN/billpay.log)"
fi
if ! wait_http "http://127.0.0.1:$CARDDEMO_BILLPAY_PORT/actuator/health" 120 '"UP"'; then
    tail -n 40 "$CARDDEMO_RUN/billpay.log" >&2
    exit 1
fi
echo "bill-payment service: http://127.0.0.1:$CARDDEMO_BILLPAY_PORT (OpenAPI /v3/api-docs)"
