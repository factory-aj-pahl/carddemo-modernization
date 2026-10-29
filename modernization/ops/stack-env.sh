# Shared settings of the stack scripts in this directory (sourced, not run).
# Every value keeps one already set in the environment, so the stack can be moved to other ports or
# another database without editing files.
_ops_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export CARDDEMO_MODERNIZATION="${CARDDEMO_MODERNIZATION:-$(cd "$_ops_dir/.." && pwd)}"
export CARDDEMO_REPO="${CARDDEMO_REPO:-$(cd "$CARDDEMO_MODERNIZATION/.." && pwd)}"
# Secrets stay in this file (keys MSSQL_SA_PASSWORD, CARDDEMO_DB_PASSWORD, RABBITMQ_PASSWORD); it is read
# only when the values are not already in the environment, and nothing here prints them.
CARDDEMO_SECRETS="${CARDDEMO_SECRETS:-$HOME/.carddemo/secrets.env}"
if [ -z "${CARDDEMO_DB_PASSWORD:-}" ] && [ -r "$CARDDEMO_SECRETS" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$CARDDEMO_SECRETS"
    set +a
fi
export CARDDEMO_DB="${CARDDEMO_DB:-carddemo}"
export CARDDEMO_API_PORT="${CARDDEMO_API_PORT:-8081}"
export CARDDEMO_WEB_PORT="${CARDDEMO_WEB_PORT:-5173}"
export CARDDEMO_AIRFLOW_PORT="${CARDDEMO_AIRFLOW_PORT:-8080}"
export CARDDEMO_BILLPAY_PORT="${CARDDEMO_BILLPAY_PORT:-8091}"
export CARDDEMO_RUN="${CARDDEMO_RUN:-$HOME/.carddemo/run}"
export AIRFLOW_HOME="${AIRFLOW_HOME:-$HOME/.carddemo/airflow}"
export CARDDEMO_OUTBOX="${CARDDEMO_OUTBOX:-$HOME/.carddemo/outbox}"
export CARDDEMO_INBOX="${CARDDEMO_INBOX:-$HOME/.carddemo/inbox}"
export SQLCMD="${SQLCMD:-/opt/mssql-tools18/bin/sqlcmd}"
export CARDDEMO_BATCH_JAR="${CARDDEMO_BATCH_JAR:-$CARDDEMO_MODERNIZATION/backend/batch/target/carddemo-batch.jar}"
export CARDDEMO_API_JAR="${CARDDEMO_API_JAR:-$CARDDEMO_MODERNIZATION/backend/app/target/carddemo-api.jar}"
export CARDDEMO_BILLPAY_JAR="${CARDDEMO_BILLPAY_JAR:-$CARDDEMO_MODERNIZATION/backend/billpay-service/target/carddemo-billpay-service.jar}"
# The Airflow simple auth manager user (airflow/env.sh) the API uses to start report runs.
export AIRFLOW_USERNAME="${AIRFLOW_USERNAME:-carddemo}"
export AIRFLOW_PASSWORD_FILE="${AIRFLOW_PASSWORD_FILE:-$AIRFLOW_HOME/simple_auth_manager_passwords.json.generated}"
unset _ops_dir

require_secrets() {
    local k
    for k in "$@"; do
        if [ -z "${!k:-}" ]; then
            echo "$k is not set: create $CARDDEMO_SECRETS (see modernization/README.md)" >&2
            exit 2
        fi
    done
}

require_file() {
    if [ ! -f "$1" ]; then
        echo "missing $1: run 'make -C modernization setup' first" >&2
        exit 2
    fi
}

batch() {
    require_file "$CARDDEMO_BATCH_JAR"
    mkdir -p "$CARDDEMO_RUN"
    CARDDEMO_LOG_DIR="$CARDDEMO_RUN" java -Xmx512m -jar "$CARDDEMO_BATCH_JAR" "$@"
}

# pid_alive FILE: the pid in FILE is a running process.
pid_alive() {
    [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null
}

# stop_group NAME: stops the process group started for NAME (pid file in $CARDDEMO_RUN), waiting up to 30 s.
stop_group() {
    local pidfile="$CARDDEMO_RUN/$1.pid" pid
    [ -f "$pidfile" ] || return 0
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
        for _ in $(seq 30); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -KILL -- "-$pid" 2>/dev/null || true
        echo "$1 stopped (pid $pid)"
    fi
    rm -f "$pidfile"
}

# wait_http URL SECONDS [PATTERN]: polls URL until it answers 2xx (and its body matches PATTERN).
wait_http() {
    local url="$1" secs="$2" pattern="${3:-}" body
    for _ in $(seq "$secs"); do
        if body="$(curl -fsS --max-time 3 "$url" 2>/dev/null)"; then
            if [ -z "$pattern" ] || printf '%s' "$body" | grep -q "$pattern"; then
                return 0
            fi
        fi
        sleep 1
    done
    echo "no answer from $url within ${secs}s" >&2
    return 1
}
