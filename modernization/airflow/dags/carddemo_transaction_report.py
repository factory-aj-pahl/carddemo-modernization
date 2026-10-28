"""Transaction report on request (CORPT00C -> JOB-TRANREPT / CBTRN03C).

Conf: {"start_date": "YYYY-MM-DD", "end_date": "YYYY-MM-DD", "user_id": "USER0001"}. user_id is the
requester: a regular user's report covers only their linked accounts (D-REPORT); an admin's, or a
request without user_id, covers every account. A missing or invalid range fails the run (exit 12,
INVALID OR MISSING REPORT DATE RANGE, Q-CBTRN03C-07).

Output: $CARDDEMO_OUTBOX/reports/<dag_run_id>/TRANREPT.txt. The reports of the last 10 runs are kept
(DF-3); storing an eleventh deletes the oldest.
"""

from __future__ import annotations

from datetime import datetime

from airflow.sdk import Param, dag, get_current_context, task
from carddemo_batch import prune_reports, report_dir, run_batch


@dag(
    dag_id="carddemo_transaction_report",
    schedule=None,
    start_date=datetime(2022, 1, 1),
    catchup=False,
    params={
        "start_date": Param(None, type=["null", "string"], description="YYYY-MM-DD"),
        "end_date": Param(None, type=["null", "string"], description="YYYY-MM-DD"),
        "user_id": Param(None, type=["null", "string"], description="requesting user ID"),
    },
    tags=["carddemo", "batch", "report"],
)
def carddemo_transaction_report():
    @task(task_id="TRANREPT")
    def tranrept() -> str:
        ctx = get_current_context()
        params = ctx["params"]
        out = report_dir(ctx["run_id"])
        out.mkdir(parents=True, exist_ok=True)
        report = out / "TRANREPT.txt"
        args = ["TRANREPT", f"startDate={params.get('start_date') or ''}", f"endDate={params.get('end_date') or ''}",
                f"reportFile={report}"]
        if params.get("user_id"):
            args.append(f"userId={params['user_id']}")
        try:
            run_batch(*args)
        except Exception:
            if not any(out.iterdir()):
                out.rmdir()
            raise
        prune_reports()
        return str(report)

    tranrept()


carddemo_transaction_report()
