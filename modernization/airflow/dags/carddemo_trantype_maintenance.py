"""JOB-MNTTRDB2 (COBTUPDT): apply a transaction type change file to the transaction_type table (weekly, on demand).

Input: the input_file param, else $CARDDEMO_INBOX/mnttrdb2/inpfile.txt. Records: column 1 action (A, U, D, *),
columns 2-3 type code, columns 4-53 description; 80-byte card images are accepted.
Exit 0 = applied, 4 = applied with rejected records, 12 = stopped and rolled back (nothing applied).
"""

from __future__ import annotations

from datetime import datetime

from airflow.sdk import Param, dag, get_current_context, task
from carddemo_batch import inbox, run_batch


@dag(
    dag_id="carddemo_trantype_maintenance",
    schedule=None,
    start_date=datetime(2022, 1, 1),
    catchup=False,
    max_active_runs=1,
    params={"input_file": Param(None, type=["null", "string"], description="change file path")},
    tags=["carddemo", "batch", "refdata"],
)
def carddemo_trantype_maintenance():
    @task(task_id="MNTTRDB2")
    def mnttrdb2() -> int:
        given = get_current_context()["params"].get("input_file")
        path = given or str(inbox() / "mnttrdb2" / "inpfile.txt")
        return run_batch("COBTUPDT", f"inputFile={path}")

    mnttrdb2()


carddemo_trantype_maintenance()
