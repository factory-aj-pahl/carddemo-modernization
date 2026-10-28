"""Daily batch cycle (B-CYCLE, D-INTEREST; runbook order). Business date = the business_date param, else
the run's logical date.

BACKUP -> POSTTRAN -> month_start -> [INTCALC, first business day of the month only] -> CREASTMT

Business days are Monday to Friday minus the dates in CARDDEMO_HOLIDAYS (none by default). The
backup (Q-TRANBKP-01) must succeed before posting; RESTORE businessDate=<date> rolls the cycle back.
Interest rows go straight into the transaction table, so there is no COMBTRAN step.

Inputs:  $CARDDEMO_INBOX/<business date>/dailytran.txt
Outputs: $CARDDEMO_OUTBOX/<business date>/dalyrejs.txt, STATEMNT.txt (text statements),
         STATEMNT.html (HTML statements)
"""

from __future__ import annotations

from datetime import date, datetime

from airflow.sdk import Param, dag, get_current_context, task
from carddemo_batch import inbox, is_first_business_day, outbox, run_batch


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
            from airflow.sdk.exceptions import AirflowFailException

            raise AirflowFailException("no business_date param and no logical date")
        return logical.date().isoformat()

    @task(task_id="BACKUP")
    def backup(day: str) -> int:
        return run_batch("BACKUP", f"businessDate={day}")

    @task(task_id="POSTTRAN")
    def posttran(day: str) -> int:
        out = outbox() / day
        out.mkdir(parents=True, exist_ok=True)
        return run_batch("POSTTRAN", f"businessDate={day}", f"dailyFile={inbox() / day / 'dailytran.txt'}",
                         f"rejectsFile={out / 'dalyrejs.txt'}")

    @task.branch(task_id="month_start")
    def month_start(day: str) -> list[str]:
        # CREASTMT is listed on both paths so the branch never skips it; on a month start it still waits
        # for INTCALC, its other upstream task.
        return ["INTCALC", "CREASTMT"] if is_first_business_day(date.fromisoformat(day)) else ["CREASTMT"]

    @task(task_id="INTCALC")
    def intcalc(day: str) -> int:
        return run_batch("INTCALC", f"businessDate={day}")

    @task(task_id="CREASTMT", trigger_rule="none_failed_min_one_success")
    def creastmt(day: str) -> int:
        out = outbox() / day
        out.mkdir(parents=True, exist_ok=True)
        return run_batch("CREASTMT", f"businessDate={day}", f"stmtFile={out / 'STATEMNT.txt'}",
                         f"htmlFile={out / 'STATEMNT.html'}")

    day = business_date()
    b, p, m, i, s = backup(day), posttran(day), month_start(day), intcalc(day), creastmt(day)
    b >> p >> m >> [i, s]
    i >> s


carddemo_daily_cycle()
