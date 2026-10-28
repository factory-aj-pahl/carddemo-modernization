"""Runs one carddemo-batch command for an Airflow task and maps its exit code (ExitCodes.java).

0 and 4 succeed (4 = completed with warnings, like a JCL step RC 4). Anything else fails the task
without retry: 8 = refused (already completed), 12 = invalid business date, 16 = usage error,
99 = abend (B-ABEND). After an abend the operator fixes the input and clears the task; Spring
Batch then restarts the job from its last committed record.
"""

from __future__ import annotations

import logging
import os
import subprocess
from pathlib import Path

from airflow.exceptions import AirflowFailException

log = logging.getLogger(__name__)

_REPO_JAR = Path(__file__).resolve().parents[2] / "backend" / "batch" / "target" / "carddemo-batch.jar"
SUCCESS = {0: "completed", 4: "completed with warnings"}
FAILURE = {8: "refused", 12: "invalid business date", 16: "usage error", 99: "abend"}


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
    raise AirflowFailException(f"{args[0]} {FAILURE.get(rc, 'failed')} (exit {rc})")
