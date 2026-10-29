"""Daily batch cycle (B-CYCLE, D-INTEREST; runbook order).

BACKUP -> POSTTRAN -> month_start -> [INTCALC, when the month's interest is due] -> CREASTMT

Schedule: one run per business date, at 22:00 UTC Monday to Friday (CYCLE_SCHEDULE, a CronTriggerTimetable). A
scheduled run's logical date is its trigger time, so the run of Friday 2022-07-01 22:00 UTC posts business date
2022-07-01. Business days are Monday to Friday minus the dates in CARDDEMO_HOLIDAYS (none by default); a scheduled run
that falls on a holiday skips every task (the run succeeds and posts nothing). catchup is off: dates missed while the
DAG was paused or the scheduler was down are not re-run automatically (only the most recent one, on unpause), and are
triggered by hand.

Manual runs: trigger with conf {"business_date": "YYYY-MM-DD"}; that date is posted as given, whatever the calendar
says. Without the param a manual run uses its logical date, like a scheduled run.

The DAG is created paused (is_paused_upon_creation), and airflow/start.sh pauses it on every start unless
CARDDEMO_CYCLE_SCHEDULE=on, so a developer stack never posts on its own. An operator turns the schedule on with
`airflow dags unpause carddemo_daily_cycle` (until the next start.sh) or by setting CARDDEMO_CYCLE_SCHEDULE=on.

Interest (month_start): INTCALC runs on the first cycle date of a calendar month whose interest is not calculated yet
(carddemo_batch.interest_due, from the jar's INTSTATUS, which reads the completed INTCALC runs that make INTCALC refuse
a second run in a month). Normally that is the first business day of the month; if that day's cycle was missed, or its
INTCALC did not complete, the next business date run in the month charges the interest. On every other date
INTCALC is skipped, so no run hits INTCALC's exit 8.

The backup (Q-TRANBKP-01) must succeed before posting. BACKUP is refused (exit 8, the run fails with nothing changed)
when any POSTTRAN run exists for the date (completed, or abended with its committed records kept, B-UOW), so a
repeated trigger takes no backup of a posted or partly posted state; RESTORE businessDate=<date> restores the latest
backup for the date, which rolls the cycle back. After a POSTTRAN abend, either clear the failed POSTTRAN task (and
its downstream tasks) in the same run, which restarts posting from its last commit, or RESTORE businessDate=<date>
and trigger the cycle for the date again. Interest rows go straight into the transaction table, so there is no
COMBTRAN step.

Inputs:  $CARDDEMO_INBOX/<business date>/dailytran.txt
Outputs: $CARDDEMO_OUTBOX/<business date>/dalyrejs.txt, STATEMNT.txt (text statements),
         STATEMNT.html (HTML statements)
"""

from __future__ import annotations

from datetime import date

from airflow.sdk import CronTriggerTimetable, Param, dag, get_current_context, task
from airflow.sdk.exceptions import AirflowFailException, AirflowSkipException
from carddemo_batch import holidays, inbox, interest_due, interest_status, is_business_day, outbox, run_batch

CYCLE_CRON = "0 22 * * 1-5"
CYCLE_TIMEZONE = "UTC"
CYCLE_SCHEDULE = CronTriggerTimetable(CYCLE_CRON, timezone=CYCLE_TIMEZONE)


def resolve_business_date(params: dict, logical) -> str:
    """The business_date param as given; else the logical date, skipping the run when that is not a business day."""
    given = params.get("business_date")
    if given:
        return given
    if logical is None:
        raise AirflowFailException("no business_date param and no logical date")
    day = logical.date()
    if not is_business_day(day, holidays()):
        raise AirflowSkipException(f"{day.isoformat()} is not a business day; no cycle")
    return day.isoformat()


# No start_date: with catchup off, unpausing a DAG that never ran waits for the next 22:00 weekday instead of
# immediately running the most recent past one.
@dag(
    dag_id="carddemo_daily_cycle",
    schedule=CYCLE_SCHEDULE,
    catchup=False,
    max_active_runs=1,
    is_paused_upon_creation=True,
    params={"business_date": Param(None, type=["null", "string"], format="date", description="YYYY-MM-DD")},
    tags=["carddemo", "batch"],
)
def carddemo_daily_cycle():
    @task
    def business_date() -> str:
        ctx = get_current_context()
        return resolve_business_date(ctx["params"], ctx.get("logical_date"))

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
        calculated, last_month = interest_status(day)
        due = interest_due(date.fromisoformat(day), calculated, last_month)
        return ["INTCALC", "CREASTMT"] if due else ["CREASTMT"]

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


daily_cycle = carddemo_daily_cycle()
