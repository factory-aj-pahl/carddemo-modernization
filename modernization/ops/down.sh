#!/usr/bin/env bash
# Stops what up.sh and billpay.sh started: web app, online API, standalone bill-payment service, and
# Airflow. Safe to run when nothing is running.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/stack-env.sh"
stop_group web
stop_group api
stop_group billpay
export AIRFLOW__API__PORT="$CARDDEMO_AIRFLOW_PORT"
"$CARDDEMO_MODERNIZATION/airflow/stop.sh"
