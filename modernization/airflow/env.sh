# Source before any airflow command: . modernization/airflow/env.sh
# Runtime state (metadata DB, logs) lives outside the repository in ~/.carddemo/airflow.
# Every setting keeps a value already present in the environment, so a second instance can run with
# its own AIRFLOW_HOME, DAGs folder, and port.
_carddemo_airflow_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export AIRFLOW_HOME="${AIRFLOW_HOME:-$HOME/.carddemo/airflow}"
export AIRFLOW__CORE__DAGS_FOLDER="${AIRFLOW__CORE__DAGS_FOLDER:-$_carddemo_airflow_dir/dags}"
export AIRFLOW__CORE__LOAD_EXAMPLES="${AIRFLOW__CORE__LOAD_EXAMPLES:-False}"
# The report DAG is started on request through the REST API, so new DAGs start unpaused.
export AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION="${AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION:-False}"
export AIRFLOW__API__HOST="${AIRFLOW__API__HOST:-127.0.0.1}"
export AIRFLOW__API__PORT="${AIRFLOW__API__PORT:-${CARDDEMO_AIRFLOW_PORT:-8080}}"
# Task workers report to the API server's execution API; it must follow the port.
export AIRFLOW__CORE__EXECUTION_API_SERVER_URL="${AIRFLOW__CORE__EXECUTION_API_SERVER_URL:-http://127.0.0.1:$AIRFLOW__API__PORT/execution/}"
# Simple auth manager: one admin user; its generated password is in
# $AIRFLOW_HOME/simple_auth_manager_passwords.json.generated (POST /auth/token for a REST API token).
export AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS="${AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS:-carddemo:admin}"
# The daily cycle DAG is created paused whatever this says; start.sh pauses it again on every start unless
# CARDDEMO_CYCLE_SCHEDULE=on (then it unpauses it, and the cycle runs at 22:00 UTC Monday to Friday).
export CARDDEMO_INBOX="${CARDDEMO_INBOX:-$HOME/.carddemo/inbox}"
export CARDDEMO_OUTBOX="${CARDDEMO_OUTBOX:-$HOME/.carddemo/outbox}"
export PATH="$HOME/.carddemo/airflow-venv/bin:$PATH"
unset _carddemo_airflow_dir
