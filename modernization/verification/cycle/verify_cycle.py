"""Schedule verification: the Airflow daily cycle against the GnuCOBOL run of the same chain.

Two scenarios on the sample data (architecture.md section 6, B-CYCLE):

  non-month-start  2022-06-10  reference POSTTRAN -> CREASTMT
  month-start      2022-07-01  reference POSTTRAN -> INTCALC (PARM=YYYYMMDD00) -> COMBTRAN sort -> CREASTMT

The reference chain is the patched GnuCOBOL programs (refrun.py), each step reading the files the
previous step wrote, with COMBTRAN reproduced by refrun.combtran(). The target chain is one
`airflow dags test carddemo_daily_cycle` run on a freshly loaded database (BACKUP, POSTTRAN,
month_start, INTCALC when the month's interest is due, which on a freshly loaded database means the
first business day, CREASTMT). The end states are compared
with compare.py: the transaction, account, and category-balance tables (exported in their legacy
layouts), the reject file, and the text and HTML statements. Run-time timestamps are the only
normalized fields (processing timestamps, and the original timestamps of interest rows).

Needs: cobc, the built jar (make -C modernization backend), the Airflow venv
(~/.carddemo/airflow-venv), CARDDEMO_DB / CARDDEMO_DB_PASSWORD for a scratch database. Airflow state
goes to $AIRFLOW_HOME (default ~/.carddemo/airflow).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODERNIZATION = HERE.parent.parent
sys.path.insert(0, str(MODERNIZATION / "verification" / "parity"))

import compare  # noqa: E402
import javarun  # noqa: E402
import refrun  # noqa: E402

SAMPLE = MODERNIZATION.parent / "app" / "data" / "ASCII"
DAGS = MODERNIZATION / "airflow" / "dags"
SCENARIOS = (("non-month-start", "2022-06-10", False), ("month-start", "2022-07-01", True))
TABLES = ("transact.txt", "acctdata.txt", "tcatbal.txt")
MASTERS = ("acctdata.txt", "carddata.txt", "custdata.txt", "cardxref.txt", "discgrp.txt", "tcatbal.txt",
           "trancatg.txt", "trantype.txt")


def reference(dataset: Path, day: str, month_start: bool, work: Path) -> Path:
    """Runs the COBOL chain; returns the directory holding its end state."""
    exp = work / "expected"
    exp.mkdir(parents=True)
    stage = work / "ref-stage"
    stage.mkdir()
    for n in MASTERS + ("dailytran.txt",):
        if (dataset / n).exists():
            shutil.copyfile(dataset / n, stage / n)
    post = work / "ref-posttran"
    rc = refrun.Runner(work / "ref-work-posttran").run("CBTRN02C", stage, post)
    if rc not in (0, 4):
        raise RuntimeError(f"reference POSTTRAN RC {rc}")
    for n in TABLES:
        shutil.copyfile(post / n, stage / n)
    shutil.copyfile(post / "dalyrejs.txt", exp / "dalyrejs.txt")
    if month_start:
        (stage / "parm.txt").write_text(day.replace("-", "") + "00\n")
        intc = work / "ref-intcalc"
        rc = refrun.Runner(work / "ref-work-intcalc").run("CBACT04C", stage, intc)
        if rc != 0:
            raise RuntimeError(f"reference INTCALC RC {rc}")
        shutil.copyfile(intc / "acctdata.txt", stage / "acctdata.txt")
        backup = refrun.records(stage / "transact.txt", 350)
        systran = refrun.records(intc / "systran.txt", 350)
        (stage / "transact.txt").write_bytes("".join(r + "\n" for r in refrun.combtran(backup, systran))
                                             .encode("latin-1"))
    stmt = work / "ref-creastmt"
    rc = refrun.Runner(work / "ref-work-creastmt").run("CBSTM03A+CBSTM03B", stage, stmt)
    if rc != 0:
        raise RuntimeError(f"reference CREASTMT RC {rc}")
    for n in TABLES:
        shutil.copyfile(stage / n, exp / n)
    for n in ("stmtfile.txt", "htmlfile.txt"):
        shutil.copyfile(stmt / n, exp / n)
    return exp


def airflow_env(inbox: Path, outbox: Path) -> dict[str, str]:
    venv = Path.home() / ".carddemo" / "airflow-venv" / "bin"
    return {
        **os.environ,
        "AIRFLOW_HOME": os.environ.get("AIRFLOW_HOME", str(Path.home() / ".carddemo" / "airflow")),
        "AIRFLOW__CORE__DAGS_FOLDER": str(DAGS),
        "AIRFLOW__CORE__LOAD_EXAMPLES": "False",
        "CARDDEMO_INBOX": str(inbox),
        "CARDDEMO_OUTBOX": str(outbox),
        "CARDDEMO_DB_PARITY": os.environ.get("CARDDEMO_DB_PARITY", os.environ.get("CARDDEMO_DB", "carddemo_test")),
        "PATH": f"{venv}:{os.environ.get('PATH', '')}",
    }


def target(dataset: Path, day: str, work: Path) -> tuple[Path, list[str]]:
    """Runs the Airflow DAG on a fresh database; returns the end-state directory and findings."""
    act = work / "actual"
    act.mkdir(parents=True)
    inbox, outbox, logs = work / "inbox", work / "outbox", work / "jar-logs"
    (inbox / day).mkdir(parents=True)
    logs.mkdir()
    shutil.copyfile(dataset / "dailytran.txt", inbox / day / "dailytran.txt")
    env = airflow_env(inbox, outbox)
    jar = javarun.JavaRunner(database=env["CARDDEMO_DB_PARITY"])
    jar.jar_run("reset", log_dir=logs)
    jar.jar_run("load", str(dataset), log_dir=logs)
    env["CARDDEMO_DB"] = jar.database
    subprocess.run(["airflow", "db", "migrate"], env=env, capture_output=True, text=True, check=True)
    p = subprocess.run(["airflow", "dags", "test", "carddemo_daily_cycle", day, "-c", json.dumps({"business_date": day})],
                       env=env, capture_output=True, text=True, check=False)
    (work / "airflow-dags-test.log").write_text(p.stdout + p.stderr)
    findings = []
    if p.returncode != 0 or "state=success" not in p.stdout + p.stderr:
        findings.append(f"DAG run did not succeed (exit {p.returncode}); see {work / 'airflow-dags-test.log'}")
    jar.jar_run("export", str(act), "files=" + ",".join(TABLES), log_dir=logs)
    for src, name in (("dalyrejs.txt", "dalyrejs.txt"), ("STATEMNT.txt", "stmtfile.txt"), ("STATEMNT.html", "htmlfile.txt")):
        f = outbox / day / src
        if f.exists():
            shutil.copyfile(f, act / name)
    return act, findings


def compare_state(exp: Path, act: Path, interest_prefix: str | None) -> list[str]:
    """compare.py per file; interest rows also have a run-time original timestamp (bytes 279-304)."""
    names = sorted({p.name for p in exp.iterdir()} | {p.name for p in act.iterdir()})
    findings = []
    for n in names:
        if not (exp / n).exists() or not (act / n).exists():
            findings.append(f"{n}: {'missing from actual' if (exp / n).exists() else 'unexpected in actual'}")
            continue
        e, a = compare._lines(exp / n), compare._lines(act / n)
        if n == "transact.txt" and interest_prefix:
            for side in (e, a):
                for i, r in enumerate(side):
                    if r.startswith(interest_prefix) and compare.DB2_TS.fullmatch(r[278:304]):
                        side[i] = r[:278] + "~" * 26 + r[304:]
        findings += compare.compare_file(n, e, a)
    return findings


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, help="keep outputs here (default: a temporary directory)")
    ap.add_argument("--dataset", type=Path, default=SAMPLE)
    a = ap.parse_args(argv)
    root = (a.out or Path(tempfile.mkdtemp(prefix="verify-cycle-"))).resolve()
    if root.exists() and any(root.iterdir()):
        raise SystemExit(f"{root} is not empty")
    results = {}
    for name, day, month_start in SCENARIOS:
        work = root / name
        try:
            exp = reference(a.dataset.resolve(), day, month_start, work)
            act, findings = target(a.dataset.resolve(), day, work)
            findings += compare_state(exp, act, day.replace("-", "") + "00" if month_start else None)
            if month_start and not any(r.startswith(day.replace("-", "") + "00") for r in compare._lines(act / "transact.txt")):
                findings.append("no interest rows: INTCALC did not run on the first business day")
        except Exception as e:  # a scenario that cannot run is a failure
            findings = [f"did not run: {e}"]
        results[name] = findings
        print(f"{'MATCH   ' if not findings else 'MISMATCH'} {name} ({day})")
        for f in findings:
            print(f"    {f}")
    (root / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    ok = all(not f for f in results.values())
    print(f"cycle verification: {sum(1 for f in results.values() if not f)}/{len(results)} scenarios matched; "
          f"outputs in {root}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
