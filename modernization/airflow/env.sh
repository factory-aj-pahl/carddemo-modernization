# Source before any airflow command: . modernization/airflow/env.sh
# Runtime state (metadata DB, logs) lives outside the repository in ~/.carddemo/airflow.
_carddemo_airflow_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export AIRFLOW_HOME="${AIRFLOW_HOME:-$HOME/.carddemo/airflow}"
export AIRFLOW__CORE__DAGS_FOLDER="$_carddemo_airflow_dir/dags"
export AIRFLOW__CORE__LOAD_EXAMPLES=False
export AIRFLOW__API__HOST=127.0.0.1
export AIRFLOW__API__PORT=8080
export CARDDEMO_INBOX="${CARDDEMO_INBOX:-$HOME/.carddemo/inbox}"
export CARDDEMO_OUTBOX="${CARDDEMO_OUTBOX:-$HOME/.carddemo/outbox}"
export PATH="$HOME/.carddemo/airflow-venv/bin:$PATH"
unset _carddemo_airflow_dir
