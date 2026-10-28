"""Pending-authorization purge (CBPAUP0J / CBPAUP0C) on its own daily schedule, outside the base cycle (Q-CA7-04).

D-PURGE: expiry_days defaults to 5 (the JCL's 00 is not carried over); an explicit number is used as given, so 0
purges every detail; a non-numeric value falls back to 5 inside the program (BR-CBPAUP0C-01). debug maps the
program's debug flag to DEBUG-level logging, off by default (Q-CBPAUP0J-02). Exit 16 (the program's 9999-ABEND
path) fails the task; checkpoints committed before the error stay, and a rerun is the restart (Q-CBPAUP0C-07).
"""

from __future__ import annotations

from datetime import datetime

from airflow.sdk.exceptions import AirflowFailException
from airflow.sdk import Param, dag, get_current_context, task
from carddemo_batch import run_batch


def sysin_card(expiry_days: object, checkpoint_frequency: int, display_frequency: int, debug: bool) -> str:
    """The CBPAUP0C SYSIN card EE,CCCCC,DDDDD,F."""
    text = str(expiry_days).strip() if expiry_days is not None else ""
    if text.isdigit():
        days = int(text)
        if days > 99:
            raise AirflowFailException(f"expiry_days {days} does not fit P-EXPIRY-DAYS PIC 9(2)")
        expiry = f"{days:02d}"
    else:
        expiry = "XX"
    for name, value in (("checkpoint_frequency", checkpoint_frequency), ("display_frequency", display_frequency)):
        if not 1 <= int(value) <= 99999:
            raise AirflowFailException(f"{name} must be 1 to 99999")
    return f"{expiry},{int(checkpoint_frequency):05d},{int(display_frequency):05d},{'Y' if debug else 'N'}"


@dag(
    dag_id="carddemo_auth_purge",
    schedule="@daily",
    start_date=datetime(2022, 1, 1),
    catchup=False,
    max_active_runs=1,
    params={
        "expiry_days": Param(5, type=["integer", "string"], description="Age in days at which a detail is purged"),
        "checkpoint_frequency": Param(5, type="integer", minimum=1, maximum=99999,
                                      description="Summaries per commit"),
        "display_frequency": Param(10, type="integer", minimum=1, maximum=99999,
                                   description="Commits between CHKP SUCCESS lines"),
        "debug": Param(False, type="boolean", description="DEBUG lines per read and delete"),
    },
    tags=["carddemo", "batch", "authorization"],
)
def carddemo_auth_purge():
    @task
    def purge_expired_authorizations() -> int:
        p = get_current_context()["params"]
        card = sysin_card(p.get("expiry_days"), p.get("checkpoint_frequency", 5), p.get("display_frequency", 10),
                          bool(p.get("debug")))
        args = ["CBPAUP0C", f"sysin={card}"]
        if p.get("debug"):
            args.append("--logging.level.com.carddemo.authorization.internal.PurgeJob=DEBUG")
        return run_batch(*args)

    purge_expired_authorizations()


carddemo_auth_purge()
