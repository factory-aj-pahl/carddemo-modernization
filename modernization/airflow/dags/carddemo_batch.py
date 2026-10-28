"""Runs one carddemo-batch command for an Airflow task and maps its exit code (ExitCodes.java).

0 and 4 succeed (4 = completed with warnings, like a JCL step RC 4). Anything else fails the task
without retry: 8 = refused (already completed), 12 = invalid business date or report range, or for COBTUPDT a
run that stopped and was rolled back, 16 = usage error (the jar prints a `usage:` line), or, for the IMS module
programs, the program's own RETURN-CODE 16 from its 9999-ABEND paragraph, 99 = abend (B-ABEND). After an abend
the operator fixes the input and clears the task; Spring Batch then restarts the job from its last committed
record.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
from datetime import date
from pathlib import Path

from airflow.sdk.exceptions import AirflowFailException

log = logging.getLogger(__name__)

_REPO_JAR = Path(__file__).resolve().parents[2] / "backend" / "batch" / "target" / "carddemo-batch.jar"
SUCCESS = {0: "completed", 4: "completed with warnings"}
FAILURE = {8: "refused", 12: "invalid date or date range", 16: "usage error", 99: "abend"}
# Commands whose RETURN-CODE 16 is the program's (ExitCodes.PROGRAM_RC16), not a usage error.
PROGRAM_RC16 = {"CBPAUP0C", "PAUDBUNL", "PAUDBLOD", "DBUNLDGS"}


def failure_label(command: str, rc: int, stderr: str = "") -> str:
    """What a failing exit code means for this command (architecture.md section 5)."""
    if rc == 16 and command in PROGRAM_RC16 and not any(line.startswith("usage:") for line in stderr.splitlines()):
        return "ended with program return code 16 (9999-ABEND)"
    if rc == 12 and command == "COBTUPDT":
        return "stopped and rolled back"
    return FAILURE.get(rc, "failed")


def inbox() -> Path:
    return Path(os.environ.get("CARDDEMO_INBOX", Path.home() / ".carddemo" / "inbox"))


def outbox() -> Path:
    return Path(os.environ.get("CARDDEMO_OUTBOX", Path.home() / ".carddemo" / "outbox"))


def run_batch(*args: str) -> int:
    """Runs the jar with args; logs the job SYSOUT; returns the exit code or fails the task."""
    jar = os.environ.get("CARDDEMO_BATCH_JAR", str(_REPO_JAR))
    logs = outbox() / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, "CARDDEMO_LOG_DIR": str(logs)}
    proc = subprocess.run(["java", "-jar", jar, *args], capture_output=True, text=True, encoding="latin-1", env=env,
                          check=False)
    for line in proc.stdout.splitlines():
        log.info("SYSOUT %s", line)
    for line in proc.stderr.splitlines():
        log.warning("STDERR %s", line)
    rc = proc.returncode
    if rc in SUCCESS:
        log.info("%s %s (exit %d)", args[0], SUCCESS[rc], rc)
        return rc
    raise AirflowFailException(f"{args[0]} {failure_label(args[0], rc, proc.stderr)} (exit {rc})")


def holidays() -> set[date]:
    """Bank holidays from CARDDEMO_HOLIDAYS (comma-separated YYYY-MM-DD); none by default."""
    raw = os.environ.get("CARDDEMO_HOLIDAYS", "")
    return {date.fromisoformat(d.strip()) for d in raw.split(",") if d.strip()}


def is_business_day(d: date, closed: set[date]) -> bool:
    return d.weekday() < 5 and d not in closed


def is_first_business_day(d: date, closed: set[date] | None = None) -> bool:
    """B-CYCLE / D-INTEREST: d is a business day (Monday to Friday, not a holiday) and no earlier day of
    its month is one."""
    closed = holidays() if closed is None else closed
    if not is_business_day(d, closed):
        return False
    return not any(is_business_day(d.replace(day=k), closed) for k in range(1, d.day))


REPORTS_KEPT = 10


def report_dir(run_id: str) -> Path:
    """$CARDDEMO_OUTBOX/reports/<dag_run_id>; the run ID is used as one path segment."""
    if not run_id or "/" in run_id or run_id in (".", ".."):
        raise AirflowFailException(f"run ID {run_id!r} cannot name a report directory")
    return outbox() / "reports" / run_id


def prune_reports(keep: int = REPORTS_KEPT) -> list[Path]:
    """DF-3 / Q-ENT-16: keep the reports of the last `keep` runs (by report file time), delete the rest.
    Returns the deleted run directories."""
    root = outbox() / "reports"
    if not root.is_dir():
        return []
    runs = [d for d in root.iterdir() if d.is_dir() and (d / "TRANREPT.txt").is_file()]
    runs.sort(key=lambda d: ((d / "TRANREPT.txt").stat().st_mtime_ns, d.name), reverse=True)
    deleted = runs[keep:]
    for d in deleted:
        shutil.rmtree(d)
        log.info("report of run %s deleted (DF-3: last %d kept)", d.name, keep)
    return deleted
