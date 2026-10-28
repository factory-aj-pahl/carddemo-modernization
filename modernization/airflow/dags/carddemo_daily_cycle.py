"""Daily batch cycle (P-SCHED, runbook order). Business date = the business_date param, else the run's logical date.

Inputs:  $CARDDEMO_INBOX/<business date>/dailytran.txt
Outputs: $CARDDEMO_OUTBOX/<business date>/dalyrejs.txt
"""

from __future__ import annotations

from datetime import datetime

from airflow.sdk import Param, dag, get_current_context, task
from carddemo_batch import inbox, outbox, run_batch


@dag(
    dag_id="carddemo_daily_cycle",
    schedule=None,
    start_date=datetime(2022, 1, 1),
    catchup=False,
    max_active_runs=1,
    params={"business_date": Param(None, type=["null", "string"], format="date", description="YYYY-MM-DD")},
    tags=["carddemo", "batch"],
)
def carddemo_daily_cycle():
    @task
    def business_date() -> str:
        ctx = get_current_context()
        given = ctx["params"].get("business_date")
        if given:
            return given
        logical = ctx.get("logical_date")
        if logical is None:
            from airflow.exceptions import AirflowFailException

            raise AirflowFailException("no business_date param and no logical date")
        return logical.date().isoformat()

    @task(task_id="POSTTRAN")
    def posttran(date: str) -> int:
        out = outbox() / date
        out.mkdir(parents=True, exist_ok=True)
        return run_batch("POSTTRAN", f"businessDate={date}", f"dailyFile={inbox() / date / 'dailytran.txt'}",
                         f"rejectsFile={out / 'dalyrejs.txt'}")

    posttran(business_date())


carddemo_daily_cycle()
